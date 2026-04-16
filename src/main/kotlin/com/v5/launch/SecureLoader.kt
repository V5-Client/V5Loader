package com.v5.launch

import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.fabricmc.loader.api.FabricLoader
import sun.misc.Unsafe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
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
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureLoader {
    private const val BACKEND_URL = "https://backend.rdbt.top"
    private const val DISK_MODULE_NAME = "V5"
    private const val DOWNLOAD_KDF_INFO = "v5-download-kek-v2"
    private const val LOADER_USER_AGENT = "V5Loader/1.1"
    private const val RAT_DETECTED_DOCS_URL = "https://rdbt.top/docs/rat-detected"
    private const val BACKEND_SPKI_SHA256_HEX = "3baa33ee9ce47074b7599de9c5cc64fe4906cb66b5500179c86a0df60b658d94"
    private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
    private val rng = SecureRandom()
    private val runtimeHwid: String by lazy { V5Native.getHwid().orEmpty().ifBlank { "ERROR" } }

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
        if (token.isNullOrBlank()) return null
        if (!isNearExpiry(token)) return token
        return refreshTokenSingleFlight(token)
    }

    @JvmStatic
    fun setJwtToken(token: String?) {
        if (token.isNullOrBlank()) return
        internalToken = token
    }

    @JvmStatic
    fun getHwid(): String = runtimeHwid

    @JvmStatic
    fun killClientHard(): Nothing = shutDownHard()

    @Synchronized
    private fun refreshTokenSingleFlight(currentToken: String): String? {
        internalToken?.let { latest ->
            if (!isNearExpiry(latest)) return latest
        }
        val refreshed = fallbackHwidLogin()
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

    private fun fallbackHwidLogin(): String? {
        if (runtimeHwid.isBlank() || runtimeHwid == "ERROR") return null
        val requestBody = """{"hwid":"$runtimeHwid"}""".toByteArray(StandardCharsets.UTF_8)

        val connection = try {
            openPinnedConnection("$BACKEND_URL/api/auth/login-hwid").apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-V5-HWID", runtimeHwid)
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
            if (responseCode != 200) return null
            val responseText = connection.inputStream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            val obj = jsonParser.parseToJsonElement(responseText).jsonObject
            if (obj["success"]?.jsonPrimitive?.booleanOrNull == false) return null
            obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: obj["token"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun run() {
        runAntiTamperChecks()
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

            if (isDevMode) {
                println("[V5] Hi Dev! Skipping loader step.")
                isPluginLoaded = true
                return
            }

            val zipBytes = downloadZip(token)
            if (zipBytes == null) {
                println("[V5] Hi Dev! Skipping loader step.")
                isDevMode = true
                isPluginLoaded = true
                return
            }

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

    @Suppress("FunctionName")
    fun V5ModLoaderCheck(): Boolean {
        return checkV5ModLoader().status == ModLoaderStatus.VALID
    }

    private fun checkV5ModLoader(): ModLoaderCheckResult {
        val modsDir = File(getGameDir(), "mods")
        val candidates = modsDir.walk()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("jar", ignoreCase = true) &&
                    (
                        file.name.startsWith("V5ModLoader", ignoreCase = true) ||
                        file.name.equals("V5ModLoader.jar", ignoreCase = true) ||
                        file.name.startsWith("v5-", ignoreCase=true)
                    )
            }
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
            connection.setRequestProperty("X-V5-HWID", runtimeHwid)
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

        try {
            val stageResult = stageModLoaderUpdateAndRelaunch(modLoaderBytes, result.candidates)
            if (stageResult.autoRelaunchPlanned) {
                println("[V5] V5ModLoader update staged. Relaunching Minecraft.")
            } else {
                println("[V5] V5ModLoader update staged. Waiting for Minecraft to close so the helper can swap jars.")
                println("[V5] If Minecraft does not reopen automatically, wait a few minutes, then relaunch it manually.")
            }
        } catch (e: Exception) {
            println("[V5] Failed to stage V5ModLoader update.")
            e.printStackTrace()
        } finally {
            Arrays.fill(modLoaderBytes, 0)
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
        return downloadEncryptedAsset("/api/download/modloader", token)
            ?: throw IOException("Backend returned DEV_LOCAL for modloader download")
    }

    private fun stageModLoaderUpdateAndRelaunch(
        modLoaderBytes: ByteArray,
        candidates: List<File>
    ): ModLoaderUpdater.StageResult {
        return ModLoaderUpdater.stageUpdateAndRelaunch(
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

    private fun downloadZip(token: String): ByteArray? {
        return downloadEncryptedAsset("/api/download/v5", token)
    }

    private fun downloadEncryptedAsset(endpointPath: String, token: String): ByteArray? {
        runAntiTamperChecks()

        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        val clientKeyPair = keyGen.generateKeyPair()
        val clientPub = Base64.getEncoder().encodeToString(clientKeyPair.public.encoded)

        val clientNonceBytes = ByteArray(16)
        rng.nextBytes(clientNonceBytes)
        val clientNonce = Base64.getEncoder().encodeToString(clientNonceBytes)

        val connection = openPinnedConnection("$BACKEND_URL$endpointPath").apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-V5-HWID", runtimeHwid)
            setRequestProperty("User-Agent", LOADER_USER_AGENT)
            setRequestProperty("X-V5-Client-Pub", clientPub)
            setRequestProperty("X-V5-Client-Nonce", clientNonce)
            connectTimeout = 10000
            readTimeout = 30000
        }

        try {
            val responseCode = connection.responseCode
            val responseText = try {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }

            val json = try {
                jsonParser.parseToJsonElement(responseText).jsonObject
            } catch (_: Exception) {
                null
            }

            if (responseCode != 200 || json?.get("success")?.jsonPrimitive?.booleanOrNull != true) {
                val errorMessage = json?.get("error")?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                throw IOException("Download failed: $errorMessage (code: $responseCode)")
            }

            if (json["mode"]?.jsonPrimitive?.contentOrNull == "DEV_LOCAL") {
                return null
            }

            val payload = json["payload"]?.jsonObject ?: throw IOException("Missing payload")
            val version = payload["version"]?.jsonPrimitive?.contentOrNull
            val serverPub = payload["server_pub_key"]?.jsonPrimitive?.contentOrNull
            val serverNonce = payload["server_nonce"]?.jsonPrimitive?.contentOrNull
            val kdfSalt = payload["kdf_salt"]?.jsonPrimitive?.contentOrNull
            val wrapIv = payload["wrap_iv"]?.jsonPrimitive?.contentOrNull
            val wrappedKey = payload["wrapped_key"]?.jsonPrimitive?.contentOrNull
            val fileIv = payload["file_iv"]?.jsonPrimitive?.contentOrNull
            val contentStr = payload["content"]?.jsonPrimitive?.contentOrNull

            if (
                version != "2" || serverPub == null || serverNonce == null ||
                kdfSalt == null || wrapIv == null || wrappedKey == null ||
                fileIv == null || contentStr == null
            ) {
                throw IOException("Invalid payload structure")
            }

            return decryptEnvelope(
                encryptedBase64 = contentStr,
                fileIvBase64 = fileIv,
                wrappedKeyBase64 = wrappedKey,
                wrapIvBase64 = wrapIv,
                serverPublicKeyBase64 = serverPub,
                kdfSaltBase64 = kdfSalt,
                clientPrivateKey = clientKeyPair.private
            )
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
                if (!actualHex.equals(BACKEND_SPKI_SHA256_HEX, ignoreCase = true)) {
                    throw SSLPeerUnverifiedException("Backend certificate pin mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return defaultTrust.acceptedIssuers
            }
        }
        init(null, arrayOf<TrustManager>(pinnedTrust), SecureRandom())
    }.socketFactory

    private fun decryptEnvelope(
        encryptedBase64: String,
        fileIvBase64: String,
        wrappedKeyBase64: String,
        wrapIvBase64: String,
        serverPublicKeyBase64: String,
        kdfSaltBase64: String,
        clientPrivateKey: PrivateKey
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC")
        val serverPublic = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(serverPublicKeyBase64))
        )

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(clientPrivateKey)
        agreement.doPhase(serverPublic, true)
        val sharedSecret = agreement.generateSecret()

        val salt = Base64.getDecoder().decode(kdfSaltBase64)
        val kekBytes = hkdfSha256(sharedSecret, salt, DOWNLOAD_KDF_INFO.toByteArray(StandardCharsets.UTF_8), 32)

        val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val wrapIv = Base64.getDecoder().decode(wrapIvBase64)
        wrapCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kekBytes, "AES"), GCMParameterSpec(128, wrapIv))
        val contentKey = wrapCipher.doFinal(Base64.getDecoder().decode(wrappedKeyBase64))

        val fileIv = Base64.getDecoder().decode(fileIvBase64)
        val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
        val plaintext = V5Native.decryptAesGcm(encryptedBytes, contentKey, fileIv)
            ?: throw IOException("Native decrypt failed")

        Arrays.fill(sharedSecret, 0)
        Arrays.fill(salt, 0)
        Arrays.fill(kekBytes, 0)
        Arrays.fill(contentKey, 0)
        return plaintext
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLen: Int): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))

        var t = ByteArray(0)
        val output = ByteArray(outputLen)
        var offset = 0
        var counter = 1

        while (offset < outputLen) {
            expandMac.reset()
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()

            val copyLen = minOf(t.size, outputLen - offset)
            System.arraycopy(t, 0, output, offset, copyLen)
            offset += copyLen
            counter++
        }

        Arrays.fill(prk, 0)
        Arrays.fill(t, 0)
        return output
    }

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

    private fun shutDownHard(): Nothing {
        val y: Unsafe by lazy {
            Unsafe::class.java.getDeclaredField("theUnsafe").let {
                it.isAccessible = true
                it[null] as Unsafe
            }
        }

        try {
            y.putAddress(0, 0)
        } catch (_: Exception) { }

        Runtime.getRuntime().exit(0)
        throw Error().also { it.stackTrace = arrayOf() }
    }

    private fun runAntiTamperChecks() {
        if (V5Native.runAntiTamperChecks()) {
            val y3k: Unsafe by lazy {
                Unsafe::class.java.getDeclaredField("theUnsafe").let {
                    it.isAccessible = true
                    it[null] as Unsafe
                }
            }
            try {
                y3k.putAddress(0, 0)
            } catch (_: Exception) {}
            Runtime.getRuntime().exit(0)
            throw Error().also { it.stackTrace = arrayOf() }
        }
    }

    fun isLoaded(): Boolean = isLoaded

    private fun getGameDir(): File {
        return FabricLoader.getInstance().gameDir.toFile()
    }

    private fun getV5ModuleDir(): File {
        return File(ModuleManager.modulesFolder, DISK_MODULE_NAME)
    }
}
