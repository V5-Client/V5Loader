package com.v5.launch

import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.engine.module.ModuleMetadata
import com.v5.api.V5Auth
import com.v5.api.V5Native
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sun.misc.Unsafe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object SecureLoader {
    private const val BACKEND_URL = "https://backend.rdbt.top"
    private const val VIRTUAL_MODULE_PREFIX = "V5"
    private const val ENTRY_POINT = "loader"
    private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 90_000L // 90 seconds
    private const val MIN_HEARTBEAT_INTERVAL_MS = 30_000L
    private const val MAX_HEARTBEAT_INTERVAL_MS = 90_000L
    private const val HEARTBEAT_MAX_RETRIES = 2
    private const val DOWNLOAD_KDF_INFO = "v5-download-kek-v2"
    private const val LOADER_USER_AGENT = "V5Loader/1.1"
    private const val BACKEND_SPKI_SHA256_HEX = "2b6e6265936bc6fa0d656fa09a36abfbb27972ca20f687f60c56fa6af0efd3d7"
    private val rng = SecureRandom()
    private val runtimeHwid: String by lazy { HWID.generateHWID() }
    private val retryableHeartbeatAuthErrors = setOf(
        "SESSION_INACTIVE",
        "JWT_EXPIRED",
        "UNAUTHORIZED"
    )

    private val jsonParser = Json {
        useAlternativeNames = true
        ignoreUnknownKeys = true
    }

    @Volatile private var isDevMode = false
    @Volatile private var isPluginLoaded = false
    @Volatile private var areMixinsApplied = false
    @Volatile private var isLoaded = false
    @Volatile private var rootMetadata: ModuleMetadata? = null
    @Volatile private var heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS

    private var heartbeatThread: Thread? = null

    private enum class HeartbeatStatus {
        SUCCESS,
        RETRYABLE_AUTH_FAILURE,
        FATAL_AUTH_FAILURE,
        TRANSIENT_FAILURE
    }

    private data class HeartbeatAttemptResult(
        val status: HeartbeatStatus,
        val errorCode: String? = null
    )

    fun run() {
        runAntiTamperChecks()

        onMixinPlugin()
        onCTMixinApplication()
        onInitialize()
    }

    fun onMixinPlugin() {
        if (isPluginLoaded) return
        if (!V5ModLoaderCheck()) {
            println("[V5] ModLoader integrity check failed.")
            shutDownHard()
        }
        println("[V5] Stage: onMixinPlugin")
        try {
            val token = V5Auth.getJwtToken()
            if (token.isNullOrBlank()) {
                println("[V5] Auto-login failed. No token passed from native loader.")
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

    fun onCTMixinApplication() {
        if (areMixinsApplied) return
        if (isDevMode) return

        println("[V5] Stage: onCTMixinApplication")
        val metadata = rootMetadata ?: return
        val mixinEntry = metadata.mixinEntry ?: return
        val entryPath = "$VIRTUAL_MODULE_PREFIX/$mixinEntry"
        JSLoader.loadVirtualMixin(entryPath)
        areMixinsApplied = true
    }

    fun V5ModLoaderCheck(): Boolean {
        val modsDir = File("./mods")
        if (!modsDir.exists()) {
            println("[V5] Mods directory not found.")
            return false
        }

        val candidates = modsDir.walk()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("jar", ignoreCase = true) &&
                    file.name.startsWith("V5ModLoader")
            }
            .toList()

        if (candidates.size != 1) {
            println("[V5] Expected one V5ModLoader jar in mods, found ${candidates.size}.")
            return false
        }

        val hash = calculateFileSha256(candidates.first())
        if (hash.isBlank()) {
            println("[V5] Failed to compute V5ModLoader hash.")
            return false
        }

        val token = V5Auth.getJwtToken()
        if (token.isNullOrBlank()) {
            println("[V5] Missing auth token for modloader integrity check.")
            return false
        }

        val url = URL("$BACKEND_URL/api/hash/modloader?hash=$hash")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-V5-HWID", runtimeHwid)
            connection.setRequestProperty("User-Agent", LOADER_USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            verifyPinnedServer(connection)
            val stream = if (responseCode in 200..299)
                connection.inputStream
            else
                connection.errorStream

            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode != 200) {
                println("[V5] check failed ($responseCode): $responseText")
                return false
            }

            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            val valid = json["valid"]?.jsonPrimitive?.booleanOrNull ?: false

            if (!valid) {
                //println("[V5] Invalid.")
                shutDownHard()
            }

            valid
        } catch (e: Exception) {
            e.printStackTrace()
            false
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

    fun onInitialize() {
        if (isLoaded) return
        println("[V5] Stage: onInitialize")

        if (isDevMode) {
            isLoaded = true
            startHeartbeat()
            return
        }

        if (!performHeartbeat()) {
            shutDownHard()
        }

        val metadata = rootMetadata
        if (metadata != null) {
            metadata.requires?.forEach { dependency ->
                if (dependency.isNotBlank()) {
                    try {
                        ModuleManager.importModule(dependency, VIRTUAL_MODULE_PREFIX)
                    } catch (e: Exception) {}
                }
            }

            Client.scheduleTask {
                try {
                    val entryPath = "$VIRTUAL_MODULE_PREFIX/$ENTRY_POINT"
                    if (JSLoader.hasVirtualFile(entryPath) || JSLoader.hasVirtualFile("$entryPath.js")) {
                        JSLoader.loadVirtualModule(entryPath)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        isLoaded = true
        startHeartbeat()
    }

    private fun startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread!!.isAlive) return

        heartbeatThread = thread(start = true, isDaemon = true, name = "V5-Heartbeat") {
            while (isLoaded) {
                try {
                    if (!performHeartbeat()) {
                        shutDownHard()
                        break
                    }
                    Thread.sleep(heartbeatIntervalMs)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun performHeartbeat(): Boolean {
        var lastErrorCode: String? = null
        val maxAttempts = HEARTBEAT_MAX_RETRIES + 1
        for (attempt in 0 until maxAttempts) {
            val result = performHeartbeatOnce()
            when (result.status) {
                HeartbeatStatus.SUCCESS -> return true
                HeartbeatStatus.TRANSIENT_FAILURE -> return true
                HeartbeatStatus.FATAL_AUTH_FAILURE -> {
                    lastErrorCode = result.errorCode
                    break
                }
                HeartbeatStatus.RETRYABLE_AUTH_FAILURE -> {
                    lastErrorCode = result.errorCode
                    if (attempt >= HEARTBEAT_MAX_RETRIES) break
                    try {
                        Thread.sleep(heartbeatRetryBackoffMs(attempt))
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
        }

        if (!lastErrorCode.isNullOrBlank()) {
            println("[V5] Heartbeat auth failed: $lastErrorCode")
        }
        println("[V5] Session expired or revoked. Exiting.")
        return false
    }

    private fun heartbeatRetryBackoffMs(attempt: Int): Long =
        when (attempt) {
            0 -> 3_000L
            1 -> 7_000L
            else -> 10_000L
        }

    private fun performHeartbeatOnce(): HeartbeatAttemptResult {
        val currentToken = V5Auth.getJwtToken()
            ?: return HeartbeatAttemptResult(
                HeartbeatStatus.FATAL_AUTH_FAILURE,
                "MISSING_TOKEN"
            )

        val connection = try {
            val url = URL("$BACKEND_URL/api/auth/heartbeat")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $currentToken")
                setRequestProperty("X-V5-HWID", runtimeHwid)
                setRequestProperty("User-Agent", LOADER_USER_AGENT)
                setRequestProperty("Content-Length", "0")
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
            }
        } catch (_: Exception) {
            return HeartbeatAttemptResult(HeartbeatStatus.TRANSIENT_FAILURE)
        }

        try {
            val responseCode = connection.responseCode
            verifyPinnedServer(connection)
            val responseText = readHttpResponse(connection, responseCode)
            if (responseCode == 200) {
                val json = parseJsonObject(responseText)
                    ?: return HeartbeatAttemptResult(HeartbeatStatus.TRANSIENT_FAILURE)
                val newToken = json["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: json["token"]?.jsonPrimitive?.contentOrNull

                if (newToken != null) {
                    V5Auth.setJwtToken(newToken)
                }
                updateHeartbeatInterval(json)
                return HeartbeatAttemptResult(HeartbeatStatus.SUCCESS)
            }
            if (responseCode == 401 || responseCode == 403) {
                val errorCode = parseErrorCode(responseText)
                if (isRetryableHeartbeatAuthFailure(responseCode, errorCode)) {
                    return HeartbeatAttemptResult(
                        HeartbeatStatus.RETRYABLE_AUTH_FAILURE,
                        errorCode
                    )
                }
                return HeartbeatAttemptResult(
                    HeartbeatStatus.FATAL_AUTH_FAILURE,
                    errorCode
                )
            }
            return HeartbeatAttemptResult(HeartbeatStatus.TRANSIENT_FAILURE)
        } catch (_: Exception) {
            return HeartbeatAttemptResult(HeartbeatStatus.TRANSIENT_FAILURE)
        } finally {
            connection.disconnect()
        }
    }

    private fun updateHeartbeatInterval(json: JsonObject) {
        val serverIntervalSeconds =
            json["heartbeat_interval_seconds"]?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        val nowSeconds = System.currentTimeMillis() / 1000
        val expiresAtSeconds =
            json["expires_at"]?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull()
                ?.takeIf { it > nowSeconds }

        val serverIntervalMs = (serverIntervalSeconds ?: 90L) * 1000L
        val halfTtlMs = expiresAtSeconds
            ?.let { ((it - nowSeconds) * 1000L) / 2L }
            ?: Long.MAX_VALUE
        val targetMs = minOf(serverIntervalMs, halfTtlMs)
        heartbeatIntervalMs = targetMs.coerceIn(
            MIN_HEARTBEAT_INTERVAL_MS,
            MAX_HEARTBEAT_INTERVAL_MS
        )
    }

    private fun readHttpResponse(
        connection: HttpURLConnection,
        responseCode: Int
    ): String {
        val stream = if (responseCode in 200..299)
            connection.inputStream
        else
            connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return try {
            jsonParser.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorCode(raw: String): String? {
        val json = parseJsonObject(raw) ?: return null
        return json["error"]?.jsonPrimitive?.contentOrNull
            ?: json["message"]?.jsonPrimitive?.contentOrNull
    }

    private fun isRetryableHeartbeatAuthFailure(
        responseCode: Int,
        errorCode: String?
    ): Boolean {
        val normalizedError = errorCode?.trim()?.uppercase()
        if (normalizedError != null && retryableHeartbeatAuthErrors.contains(normalizedError)) {
            return true
        }
        return responseCode == 401 && normalizedError.isNullOrBlank()
    }

    private fun downloadZip(token: String): ByteArray? {
        runAntiTamperChecks()

        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        val clientKeyPair = keyGen.generateKeyPair()
        val clientPub = Base64.getEncoder().encodeToString(clientKeyPair.public.encoded)

        val clientNonceBytes = ByteArray(16)
        rng.nextBytes(clientNonceBytes)
        val clientNonce = Base64.getEncoder().encodeToString(clientNonceBytes)

        val connection = (URL("$BACKEND_URL/api/download/v5").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-V5-HWID", runtimeHwid)
            setRequestProperty("User-Agent", LOADER_USER_AGENT)
            setRequestProperty("X-V5-Client-Pub", clientPub)
            setRequestProperty("X-V5-Client-Nonce", clientNonce)
            connectTimeout = 10000
            readTimeout = 30000
        }

        val responseCode = connection.responseCode
        verifyPinnedServer(connection)
        val responseText = try {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }

        val json = try {
            jsonParser.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            null
        }

        if (responseCode != 200 || json?.get("success")?.jsonPrimitive?.booleanOrNull != true) {
            val errorMessage = json?.get("error")?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            println("[V5] Download failed: $errorMessage (code: $responseCode)")
            shutDownHard()
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
    }

    private fun verifyPinnedServer(connection: HttpURLConnection) {
        val https = connection as? HttpsURLConnection ?: return
        val cert = https.serverCertificates.firstOrNull() ?: throw SSLPeerUnverifiedException("Missing server cert")
        val publicKey = cert.publicKey?.encoded ?: throw SSLPeerUnverifiedException("Missing server public key")
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val actualHex = digest.joinToString("") { "%02x".format(it) }
        if (!actualHex.equals(BACKEND_SPKI_SHA256_HEX, ignoreCase = true)) {
            throw SSLPeerUnverifiedException("Backend certificate pin mismatch")
        }
    }

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
        JSLoader.clearVirtualFiles()

        val zipStream = ZipInputStream(ByteArrayInputStream(zipData))
        val tempAssetsDir = File(File("./config"), "ChatTriggers/assets/").apply { mkdirs() }

        try {
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                try {
                    if (!entry.isDirectory) {
                        val result = processZipEntry(zipStream, entry, tempAssetsDir)
                        if (result is ZipEntryResult.VirtualFile && result.isRootMetadata) {
                            rootMetadata = result.metadata
                        }
                    }
                } catch (e: Exception) {
                    // Ignored
                } finally {
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        } finally {
            zipStream.close()
        }
    }

    private sealed class ZipEntryResult {
        data class VirtualFile(val isRootMetadata: Boolean, val metadata: ModuleMetadata?) : ZipEntryResult()
        object AssetFile : ZipEntryResult()
        object Skipped : ZipEntryResult()
    }

    private fun processZipEntry(zipStream: ZipInputStream, entry: ZipEntry, assetsDir: File): ZipEntryResult {
        val rawName = entry.name
        val entryName = rawName.replace('\\', '/')
            .removePrefix("$VIRTUAL_MODULE_PREFIX/")
            .removePrefix("/")
            .trim()

        if (entryName.isEmpty() || entryName.startsWith(".") || entryName.contains("/."))
            return ZipEntryResult.Skipped

        val virtualPath = "$VIRTUAL_MODULE_PREFIX/$entryName"
        val extension = entryName.substringAfterLast('.', "").lowercase()
        val isInAssets = entryName.startsWith("assets/")

        return when {
            extension == "js" || (extension == "json" && !isInAssets) -> {
                val content = String(zipStream.readAllBytes(), StandardCharsets.UTF_8)
                JSLoader.addVirtualFile(virtualPath, content)
                val isRootMetadata = entryName == "metadata.json"
                var metadata: ModuleMetadata? = null
                if (isRootMetadata) {
                    metadata = try {
                        jsonParser.decodeFromString<ModuleMetadata>(content)
                    } catch (e: Exception) {
                        null
                    }
                }
                ZipEntryResult.VirtualFile(isRootMetadata, metadata)
            }
            extension in listOf("png", "jpg", "jpeg", "gif", "svg", "wav", "ogg", "mp3", "json", "exe") -> {
                val finalName = if (entryName.startsWith("assets/")) {
                    entryName.removePrefix("assets/")
                } else {
                    entryName
                }

                val assetFile = File(assetsDir, finalName)
                val normalizedAssetsRoot = assetsDir.canonicalFile.toPath().normalize()
                val normalizedTarget = assetFile.canonicalFile.toPath().normalize()
                if (!normalizedTarget.startsWith(normalizedAssetsRoot)) {
                    return ZipEntryResult.Skipped
                }
                assetFile.parentFile?.mkdirs()
                FileOutputStream(assetFile).use { fos -> zipStream.copyTo(fos) }
                ZipEntryResult.AssetFile
            }
            else -> ZipEntryResult.Skipped
        }
    }

    fun reload() {
        isLoaded = false
        isPluginLoaded = false
        areMixinsApplied = false
        isDevMode = false
        rootMetadata = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        JSLoader.clearVirtualFiles()
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
}
