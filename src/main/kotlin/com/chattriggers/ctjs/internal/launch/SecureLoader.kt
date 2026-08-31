package com.chattriggers.ctjs.internal.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.v5.loader.internal.V5Crypto
import com.v5.loader.internal.V5Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.util.Util
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

internal object SecureLoader {
    private const val BACKEND_URL = "https://backend.rdbt.top"
    private const val DISK_MODULE_NAME = "V5"
    private const val LOADER_USER_AGENT = "V5Loader/1.1"
    private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
    private const val CTJS_ERROR_REPORT_INTERVAL_MS = 15 * 60 * 1000L
    private const val SESSION_DIR_NAME = ".v5"
    private const val SESSION_FILE_NAME = "session.json"
    private const val GITHUB_API_HOST = "api.github.com"
    private const val GITHUB_HOST = "github.com"
    private const val GITHUB_REPOSITORY = "V5-Client/V5"
    private const val MODULE_ASSET_NAME = "V5-Mojmap.zip"
    private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
    private val RELEASE_TAG_REGEX = Regex("[A-Za-z0-9._-]+")

    private val jsonParser = Json {
        useAlternativeNames = true
        ignoreUnknownKeys = true
    }

    @Volatile private var isDevMode = false
    @Volatile private var isPluginLoaded = false
    @Volatile private var isLoaded = false
    @Volatile private var internalToken: String? = null
    @Volatile private var didConsumeInitialLoaderToken = false
    private var lastCtjsErrorReportAt = 0L

    @JvmStatic
    fun getJwtToken(): String? {
        val token = internalToken
        if (!token.isNullOrBlank()) return token

        if (!didConsumeInitialLoaderToken) {
            synchronized(this) {
                if (!didConsumeInitialLoaderToken) {
                    val loaderToken = V5TokenSource.consumeToken()
                    if (!loaderToken.isNullOrBlank()) {
                        internalToken = loaderToken
                    }
                    didConsumeInitialLoaderToken = true
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

    fun reportCtjsJavascriptError(
        kind: String,
        message: String?,
        errorClass: String? = null,
        sourceName: String? = null,
        line: Int? = null,
        lineSource: String? = null,
        lineOffset: Int? = null,
        stack: String? = null,
    ) {
        if (isDevMode) return

        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastCtjsErrorReportAt < CTJS_ERROR_REPORT_INTERVAL_MS) return
            lastCtjsErrorReportAt = now
        }

        val body = buildJsonObject {
            put("kind", kind)
            message?.let { put("message", it.take(1000)) }
            errorClass?.let { put("error_class", it.take(200)) }
            sourceName?.let { put("source_name", it.take(300)) }
            line?.let { put("line", it) }
            lineSource?.let { put("line_source", it.take(1000)) }
            lineOffset?.let { put("line_offset", it) }
            stack?.let { put("stack", it.take(5000)) }
            ModuleManager.cachedModules.firstOrNull { it.name == DISK_MODULE_NAME }?.metadata?.version?.let { put("script_version", it.take(100)) }
            put("loader_version", LOADER_USER_AGENT)
            put("mod_version", CTJS.MOD_VERSION)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        Util.backgroundExecutor().execute {
            val token = getFreshJwtToken() ?: return@execute
            val connection = try {
                openBackendConnection("$BACKEND_URL/api/logs/ctjs-errors").apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", LOADER_USER_AGENT)
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                }
            } catch (_: Exception) {
                return@execute
            }

            try {
                connection.outputStream.use { it.write(body) }
                (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.close()
            } catch (_: Exception) {
            } finally {
                connection.disconnect()
            }
        }
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
        val requestBody = buildJsonObject { put("refresh_token", refreshToken) }.toString()
            .toByteArray(StandardCharsets.UTF_8)

        val connection = try {
            openBackendConnection("$BACKEND_URL/api/auth/refresh").apply {
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
            val json = buildJsonObject {
                put("refresh_token", refreshToken)
                put("updated_at", System.currentTimeMillis() / 1000L)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
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

    fun run() {
        onMixinPlugin()
        onInitialize()
    }

    fun onMixinPlugin() {
        if (isPluginLoaded) return
        println("[V5] Stage: onMixinPlugin")
        try {
            val token = getFreshJwtToken()
            if (token.isNullOrBlank()) {
                println("[V5] No loader auth token available.")
                shutDownHard()
            }

            val modulePath = getV5ModuleDir()
            if (modulePath.exists() && isLocalDeveloperModeEnabled()) {
                isDevMode = true
                println("[V5] Developer mode is active. Skipping V5 module download.")
                isPluginLoaded = true
                return
            }

            val zipBytes = downloadZip()
            processZip(zipBytes)
            Arrays.fill(zipBytes, 0)
            isPluginLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
            shutDownHard()
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

    private fun downloadZip(): ByteArray {
        val release = V5Http.httpsGet(GITHUB_API_HOST, "/repos/$GITHUB_REPOSITORY/releases/latest")
            .takeIf { it.isNotBlank() }
            ?.let { jsonParser.parseToJsonElement(it).jsonObject }
            ?: throw IOException("Failed to read the latest V5 GitHub workflow release")
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf(RELEASE_TAG_REGEX::matches)
            ?: throw IOException("Latest V5 release has an invalid tag")
        val asset = release["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.singleOrNull { it["name"]?.jsonPrimitive?.contentOrNull == MODULE_ASSET_NAME }
            ?: throw IOException("Latest V5 release does not contain $MODULE_ASSET_NAME")
        val bytes = V5Http.httpsGetBytes(
            GITHUB_HOST,
            "/$GITHUB_REPOSITORY/releases/download/$tag/$MODULE_ASSET_NAME",
        ) ?: throw IOException("Failed to download $MODULE_ASSET_NAME from GitHub")
        return bytes
    }

    private fun openBackendConnection(url: String): HttpsURLConnection =
        URI.create(url).toURL().openConnection() as HttpsURLConnection

    private fun processZip(zipData: ByteArray) {
        val moduleDir = getV5ModuleDir()
        val parentDir = moduleDir.parentFile
        val transactionDir = parentDir.parentFile ?: throw IOException("Unable to resolve module transaction directory")
        Files.createDirectories(parentDir.toPath())
        val stagingDir = Files.createTempDirectory(transactionDir.toPath(), ".$DISK_MODULE_NAME-stage-").toFile()
        var backupDir: File? = null

        try {
            ZipInputStream(ByteArrayInputStream(zipData)).use { zipStream ->
                var entry: ZipEntry? = zipStream.nextEntry
                while (entry != null) {
                    try {
                        if (!entry.isDirectory) {
                            processZipEntry(zipStream, entry, stagingDir)
                        }
                    } finally {
                        zipStream.closeEntry()
                    }
                    entry = zipStream.nextEntry
                }
            }

            val metadataFile = File(stagingDir, "metadata.json")
            if (!metadataFile.isFile) throw IOException("Downloaded module is missing metadata.json")
            jsonParser.parseToJsonElement(metadataFile.readText(Charsets.UTF_8)).jsonObject

            try {
                if (moduleDir.exists()) {
                    val backupPath = Files.createTempDirectory(transactionDir.toPath(), ".$DISK_MODULE_NAME-backup-")
                    Files.delete(backupPath)
                    backupDir = backupPath.toFile()
                    moveDirectory(moduleDir, backupDir)
                }

                moveDirectory(stagingDir, moduleDir)
            } catch (swapError: Exception) {
                val backup = backupDir
                if (backup != null && backup.exists() && !moduleDir.exists()) {
                    try {
                        moveDirectory(backup, moduleDir)
                        backupDir = null
                    } catch (rollbackError: Exception) {
                        swapError.addSuppressed(rollbackError)
                    }
                }
                throw swapError
            }

            backupDir?.deleteRecursively()
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
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

    private fun shutDownHard(): Nothing {
        Runtime.getRuntime().halt(0)
        throw IllegalStateException("V5 loader aborted due to unrecoverable error")
    }

    fun isLoaded(): Boolean = isLoaded

    private fun getGameDir(): File {
        return FabricLoader.getInstance().gameDir.toFile()
    }

    private fun getV5ModuleDir(): File {
        return File(File(CTJS.MODULES_FOLDER), DISK_MODULE_NAME)
    }
}
