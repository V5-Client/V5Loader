package com.v5.launch

import com.chattriggers.ctjs.CTJS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.fabricmc.loader.api.FabricLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SecureLoader {
    private const val BACKEND_URL = "https://backend.rdbt.top"
    private const val DISK_MODULE_NAME = "V5"
    private const val LOADER_USER_AGENT = "V5Loader/1.1"
    private const val RAT_DETECTED_DOCS_URL = "https://rdbt.top/docs/rat-detected"
    private const val BACKEND_SPKI_SHA256_HEX = "3baa33ee9ce47074b7599de9c5cc64fe4906cb66b5500179c86a0df60b658d94"
    private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
    private const val SESSION_DIR_NAME = ".v5"
    private const val SESSION_FILE_NAME = "session.json"

    private val jsonParser = Json {
        useAlternativeNames = true
        ignoreUnknownKeys = true
    }
    private val pinnedSslSocketFactory by lazy { buildPinnedSslSocketFactory() }

    @Volatile private var isDevMode = false
    @Volatile private var isPluginLoaded = false
    @Volatile private var isLoaded = false
    @Volatile private var internalToken: String? = null
    @Volatile private var didConsumeInitialNativeToken = false

    private enum class ModLoaderStatus {
        VALID,
        OUTDATED,
        INVALID_TAMPERED,
        INVALID_INSTALLATION,
        CHECK_FAILED
    }

    private data class ModLoaderCheckResult(
        val status: ModLoaderStatus,
        val candidates: List<File>,
        val message: String
    )

    @JvmStatic
    fun getJwtToken(): String? {
        val token = internalToken
        if (!token.isNullOrBlank()) return token

        if (!didConsumeInitialNativeToken) {
            synchronized(this) {
                if (!didConsumeInitialNativeToken) {
                    val nativeToken = V5Native.consumeToken()
                    if (!nativeToken.isNullOrBlank()) {
                        internalToken = nativeToken
                    }
                    didConsumeInitialNativeToken = true
                }
            }
        }

        return internalToken
    }

    @JvmStatic
    fun getFreshJwtToken(): String? {
        val token = getJwtToken()
        if (token.isNullOrBlank()) return refreshTokenSingleFlight("")
        if (!isNearExpiry(token)) return token
        return refreshTokenSingleFlight(token)
    }

    @JvmStatic
    fun setJwtToken(token: String?) {
        if (token.isNullOrBlank()) return
        internalToken = token
    }

    @JvmStatic
    fun killClientHard(): Nothing = shutDownHard()

    @Synchronized
    private fun refreshTokenSingleFlight(currentToken: String): String? {
        internalToken?.let { latest ->
            if (!isNearExpiry(latest)) return latest
        }
        val refreshed = refreshWithStoredRefreshToken()
        if (!refreshed.isNullOrBlank()) {
            internalToken = refreshed
            return refreshed
        }
        return internalToken?.takeUnless { isExpired(it) }
            ?: currentToken.takeUnless { isExpired(it) }
    }

    private fun isNearExpiry(token: String): Boolean {
        val exp = parseTokenExpiry(token) ?: return true
        val now = System.currentTimeMillis() / 1000L
        return exp <= now + TOKEN_EXPIRY_SKEW_SECONDS
    }

    private fun isExpired(token: String): Boolean {
        val exp = parseTokenExpiry(token) ?: return true
        val now = System.currentTimeMillis() / 1000L
        return exp <= now
    }

    private fun parseTokenExpiry(token: String): Long? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
            val payload =
                jsonParser.parseToJsonElement(String(payloadBytes, StandardCharsets.UTF_8)).jsonObject
            payload["exp"]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshWithStoredRefreshToken(): String? {
        val refreshToken = readRefreshTokenFromSessionFile() ?: return null
        val requestBody = """{"refresh_token":"${escapeJson(refreshToken)}"}"""
            .toByteArray(StandardCharsets.UTF_8)

        val connection = try {
            openPinnedConnection("$BACKEND_URL/api/auth/refresh").apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", LOADER_USER_AGENT)
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
            }
        } catch (_: Exception) {
            return null
        }

        return try {
            connection.outputStream.use { it.write(requestBody) }
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            if (responseText.isBlank()) return null

            val obj = jsonParser.parseToJsonElement(responseText).jsonObject
            if (responseCode != 200) {
                val errorCode = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (
                    errorCode == "INVALID_REFRESH_TOKEN" ||
                    errorCode == "REFRESH_TOKEN_EXPIRED" ||
                    errorCode == "REFRESH_TOKEN_REUSED" ||
                    errorCode == "SESSION_REVOKED"
                ) {
                    clearSessionFile()
                }
                return null
            }

            val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: obj["token"]?.jsonPrimitive?.contentOrNull
            val rotatedRefresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
            if (accessToken.isNullOrBlank() || rotatedRefresh.isNullOrBlank()) {
                return null
            }
            persistRefreshToken(rotatedRefresh)
            accessToken
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getSessionFile(): File {
        return File(File(getGameDir(), SESSION_DIR_NAME), SESSION_FILE_NAME)
    }

    private fun readRefreshTokenFromSessionFile(): String? {
        val file = getSessionFile()
        if (!file.exists() || !file.isFile) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val obj = jsonParser.parseToJsonElement(content).jsonObject
            obj["refresh_token"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun persistRefreshToken(refreshToken: String) {
        if (refreshToken.isBlank()) return
        val file = getSessionFile()
        try {
            file.parentFile?.mkdirs()
            val escaped = escapeJson(refreshToken)
            val json = """{"refresh_token":"$escaped","updated_at":${System.currentTimeMillis() / 1000L}}"""
            file.writeText(json, Charsets.UTF_8)
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (_: Exception) {
        }
    }

    private fun clearSessionFile() {
        try {
            val file = getSessionFile()
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 8) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }
    }

    fun run() {
        onMixinPlugin()
        onInitialize()
    }

    fun onMixinPlugin() {
        if (isPluginLoaded) return
        if (!ensureV5ModLoaderInstalled()) {
            shutDownHard()
        }
        println("[V5] Stage: onMixinPlugin")
        try {
            val token = getFreshJwtToken()
            if (token.isNullOrBlank()) {
                println("[V5] No token passed from native loader.")
                shutDownHard()
            }

            val modulePath = getV5ModuleDir()
            if (modulePath.exists() && isLocalDeveloperModeEnabled()) {
                isDevMode = true
                println("[V5] Developer mode with existing V5 module path. Skipping V5 module download.")
                isPluginLoaded = true
                return
            }

            val zipBytes = downloadZip(token)
            processZip(zipBytes)
            Arrays.fill(zipBytes, 0)
            isPluginLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
            shutDownHard()
        }
    }

    private fun ensureV5ModLoaderInstalled(): Boolean {
        val result = checkV5ModLoader()
        return when (result.status) {
            ModLoaderStatus.VALID -> true
            ModLoaderStatus.OUTDATED,
            ModLoaderStatus.INVALID_INSTALLATION -> {
                println("[V5] ${result.message}")
                tryAutoUpdateModLoader(result)
                false
            }
            ModLoaderStatus.INVALID_TAMPERED -> {
                println("[V5] ${result.message}")
                openRatDetectedDocsPage()
                false
            }
            ModLoaderStatus.CHECK_FAILED -> {
                println("[V5] ${result.message}")
                false
            }
        }
    }

    private fun isLocalDeveloperModeEnabled(): Boolean {
        return try {
            val stateFile = File(File(CTJS.MODULES_FOLDER, "V5Config"), "developerMode.json")
            stateFile.isFile &&
                jsonParser.parseToJsonElement(stateFile.readText(Charsets.UTF_8))
                    .jsonObject["enabled"]
                    ?.jsonPrimitive
                    ?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("FunctionName")
    fun V5ModLoaderCheck(): Boolean {
        return checkV5ModLoader().status == ModLoaderStatus.VALID
    }

    private fun checkV5ModLoader(): ModLoaderCheckResult {
        val modsDir = File(getGameDir(), "mods")
        val candidates = modsDir.walk()
            .filter { file -> file.isFile && isV5ModLoaderJar(file.name) }
            .toList()

        if (candidates.size != 1) {
            return ModLoaderCheckResult(
                status = ModLoaderStatus.INVALID_INSTALLATION,
                candidates = candidates,
                message = "Expected one V5ModLoader jar in mods, found ${candidates.size}. Repairing install."
            )
        }

        val hash = calculateFileSha256(candidates.first())
        if (hash.isBlank()) {
            return ModLoaderCheckResult(
                status = ModLoaderStatus.INVALID_INSTALLATION,
                candidates = candidates,
                message = "Failed to compute V5ModLoader hash. Repairing install."
            )
        }

        val token = getFreshJwtToken()
        if (token.isNullOrBlank()) {
            return ModLoaderCheckResult(
                status = ModLoaderStatus.CHECK_FAILED,
                candidates = candidates,
                message = "Missing auth token for modloader integrity check."
            )
        }

        val connection = openPinnedConnection("$BACKEND_URL/api/hash/modloader?hash=$hash")

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("User-Agent", LOADER_USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299)
                connection.inputStream
            else
                connection.errorStream

            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode != 200) {
                return ModLoaderCheckResult(
                    status = ModLoaderStatus.CHECK_FAILED,
                    candidates = candidates,
                    message = "Modloader integrity check failed ($responseCode): $responseText"
                )
            }

            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            val integrity = json["integrity"]?.jsonPrimitive?.contentOrNull?.lowercase()

            when (integrity) {
                "valid" -> ModLoaderCheckResult(
                    status = ModLoaderStatus.VALID,
                    candidates = candidates,
                    message = "V5ModLoader integrity verified."
                )
                "outdated" -> ModLoaderCheckResult(
                    status = ModLoaderStatus.OUTDATED,
                    candidates = candidates,
                    message = "V5ModLoader integrity is outdated. Downloading the latest build from backend."
                )
                "invalid" -> ModLoaderCheckResult(
                    status = ModLoaderStatus.INVALID_TAMPERED,
                    candidates = candidates,
                    message = "V5ModLoader integrity is invalid. A malicious modified jar may be installed. Opened $RAT_DETECTED_DOCS_URL for more info."
                )
                else -> ModLoaderCheckResult(
                    status = ModLoaderStatus.CHECK_FAILED,
                    candidates = candidates,
                    message = "Modloader integrity check returned unknown state: ${integrity ?: "missing"}"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ModLoaderCheckResult(
                status = ModLoaderStatus.CHECK_FAILED,
                candidates = candidates,
                message = "Failed to verify V5ModLoader against backend."
            )
        } finally {
            connection.disconnect()
        }
    }

    fun calculateFileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int

            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun tryAutoUpdateModLoader(result: ModLoaderCheckResult) {
        val token = getFreshJwtToken()
        if (token.isNullOrBlank()) {
            println("[V5] Missing auth token for automatic V5ModLoader repair.")
            return
        }

        val modLoaderBytes = try {
            downloadModLoaderJar(token)
        } catch (e: Exception) {
            println("[V5] Failed to download the latest V5ModLoader.")
            e.printStackTrace()
            return
        }

        var updateStaged = false
        try {
            stageModLoaderUpdateAndRelaunch(modLoaderBytes, result.candidates)
            updateStaged = true
            println("[V5] V5ModLoader update staged. Closing Minecraft now so the helper can swap jars.")
        } catch (e: Exception) {
            println("[V5] Failed to stage V5ModLoader update.")
            e.printStackTrace()
        } finally {
            Arrays.fill(modLoaderBytes, 0)
        }

        if (updateStaged) {
            forceCloseForModLoaderUpdate()
        }
    }

    private fun openRatDetectedDocsPage() {
        if (tryOpenUrl(RAT_DETECTED_DOCS_URL)) return
        println("[V5] Failed to open browser automatically. Visit $RAT_DETECTED_DOCS_URL")
    }

    private fun tryOpenUrl(url: String): Boolean {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                val desktop = java.awt.Desktop.getDesktop()
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(URI(url))
                    return true
                }
            }
        } catch (_: Exception) {}

        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            osName.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }

        return try {
            ProcessBuilder(command).start()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadModLoaderJar(token: String): ByteArray {
        return downloadAsset("/api/download/modloader", token)
    }

    private fun stageModLoaderUpdateAndRelaunch(
        modLoaderBytes: ByteArray,
        candidates: List<File>
    ) {
        ModLoaderUpdater.stageUpdateAndRelaunch(
            gameDir = getGameDir(),
            modLoaderBytes = modLoaderBytes,
            candidates = candidates
        )
    }

    fun onInitialize() {
        if (isLoaded) return
        println("[V5] Stage: onInitialize")

        if (isDevMode) {
            isLoaded = true
            return
        }

        if (getFreshJwtToken().isNullOrBlank()) {
            println("[V5] Session expired or revoked. Exiting.")
            shutDownHard()
        }

        isLoaded = true
    }

    private fun downloadZip(token: String): ByteArray {
        return downloadAsset("/api/download/v5", token)
    }

    private fun downloadAsset(endpointPath: String, token: String): ByteArray {
        val connection = openPinnedConnection("$BACKEND_URL$endpointPath").apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", LOADER_USER_AGENT)
            connectTimeout = 10000
            readTimeout = 30000
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val responseText = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                val errorMessage = try {
                    jsonParser.parseToJsonElement(responseText).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) {
                    null
                }
                throw IOException("Download failed: ${errorMessage ?: "Unknown error"} (code: $responseCode)")
            }

            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openPinnedConnection(url: String): HttpsURLConnection {
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedSslSocketFactory
        }
    }

    private fun buildPinnedSslSocketFactory() = SSLContext.getInstance("TLS").apply {
        val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustFactory.init(null as java.security.KeyStore?)
        val defaultTrust = trustFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw SSLPeerUnverifiedException("Default trust manager unavailable")
        val pinnedTrust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrust.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrust.checkServerTrusted(chain, authType)
                val leaf = chain?.firstOrNull() ?: throw SSLPeerUnverifiedException("Missing server cert")
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                val actualHex = digest.joinToString("") { "%02x".format(it) }
                //if (!actualHex.equals(BACKEND_SPKI_SHA256_HEX, ignoreCase = true)) {
                //    throw SSLPeerUnverifiedException("Backend certificate pin mismatch")
                //}
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return defaultTrust.acceptedIssuers
            }
        }
        init(null, arrayOf<TrustManager>(pinnedTrust), SecureRandom())
    }.socketFactory

    private fun processZip(zipData: ByteArray) {
        val moduleDir = getV5ModuleDir()
        if (moduleDir.exists()) {
            moduleDir.deleteRecursively()
        }
        moduleDir.mkdirs()

        val zipStream = ZipInputStream(ByteArrayInputStream(zipData))

        try {
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                try {
                    if (!entry.isDirectory) {
                        processZipEntry(zipStream, entry, moduleDir)
                    }
                } catch (e: Exception) {
                    println("Error processing zip entry: ${e.message}")
                    shutDownHard()
                } finally {
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        } finally {
            zipStream.close()
        }
    }

    private fun processZipEntry(zipStream: ZipInputStream, entry: ZipEntry, moduleDir: File) {
        val rawName = entry.name
        val entryName = rawName.replace('\\', '/')
            .removePrefix("$DISK_MODULE_NAME/")
            .removePrefix("/")
            .trim()

        if (entryName.isEmpty() || entryName.startsWith(".") || entryName.contains("/."))
            return

        val moduleFile = File(moduleDir, entryName)
        val normalizedRoot = moduleDir.canonicalFile.toPath().normalize()
        val normalizedTarget = moduleFile.canonicalFile.toPath().normalize()
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            return
        }

        val bytes = zipStream.readAllBytes()
        moduleFile.parentFile?.mkdirs()
        FileOutputStream(moduleFile).use { fos -> fos.write(bytes) }
    }

    fun reload() {
        isLoaded = false
        isPluginLoaded = false
        isDevMode = false
        run()
    }

    private fun forceCloseForModLoaderUpdate(): Nothing {
        try {
            System.out.flush()
            System.err.flush()
            Thread.sleep(150)
        } catch (_: Exception) {
        }

        Runtime.getRuntime().halt(0)
        throw IllegalStateException("Failed to terminate process after staging V5ModLoader update")
    }

    private fun shutDownHard(): Nothing {
        Runtime.getRuntime().halt(0)
        throw IllegalStateException("V5 loader aborted due to unrecoverable error")
    }

    fun isLoaded(): Boolean = isLoaded

    private fun isV5ModLoaderJar(fileName: String): Boolean {
        if (!fileName.endsWith(".jar", ignoreCase = true)) return false
        if (fileName.startsWith("V5ModLoader", ignoreCase = true)) return true
        // Gradle publish name (e.g. v5-1.0.0.jar), not V5-Loader.jar (case-insensitive v5- prefix).
        return fileName.startsWith("v5-", ignoreCase = true) &&
            !fileName.startsWith("V5-Loader", ignoreCase = true)
    }

    private fun getGameDir(): File {
        return FabricLoader.getInstance().gameDir.toFile()
    }

    private fun getV5ModuleDir(): File {
        return File(File(CTJS.MODULES_FOLDER), DISK_MODULE_NAME)
    }
}
