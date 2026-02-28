package com.v5.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.engine.module.ModuleMetadata
import com.v5.api.V5Auth
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sun.misc.Unsafe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
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
    private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 150_000L // 2 minutes 30 seconds
    private const val DOWNLOAD_KDF_INFO = "v5-download-kek-v2"
    private val rng = SecureRandom()

    @Volatile private var isDevMode = false
    @Volatile private var isPluginLoaded = false
    @Volatile private var areMixinsApplied = false
    @Volatile private var isLoaded = false
    @Volatile private var rootMetadata: ModuleMetadata? = null

    private var heartbeatThread: Thread? = null

    fun run() {
        runAntiTamperChecks()

        onMixinPlugin()
        onCTMixinApplication()
        onInitialize()
    }

    fun onMixinPlugin() {
        if (isPluginLoaded) return
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

    fun onInitialize() {
        if (isLoaded) return
        println("[V5] Stage: onInitialize")

        if (isDevMode) {
            isLoaded = true
            startHeartbeat()
            return
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
                    performHeartbeat()
                    Thread.sleep(DEFAULT_HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun performHeartbeat() {
        val currentToken = V5Auth.getJwtToken() ?: return

        try {
            val url = URL("$BACKEND_URL/api/auth/heartbeat")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $currentToken")
            connection.setRequestProperty("User-Agent", "V5Loader/1.0")
            connection.setRequestProperty("Content-Length", "0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().readText()
                val json = CTJS.Companion.json.parseToJsonElement(responseText).jsonObject
                val newToken = json["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: json["token"]?.jsonPrimitive?.contentOrNull

                if (newToken != null) {
                    V5Auth.setJwtToken(newToken)
                }
            } else if (responseCode == 401 || responseCode == 403) {
                println("[V5] Session expired or revoked. Exiting.")
                shutDownHard()
            }
        } catch (e: Exception) {}
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
            setRequestProperty("User-Agent", "V5Loader/1.2")
            setRequestProperty("X-V5-Client-Pub", clientPub)
            setRequestProperty("X-V5-Client-Nonce", clientNonce)
            connectTimeout = 10000
            readTimeout = 30000
        }

        val responseCode = connection.responseCode
        val responseText = try {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }

        val json = try {
            CTJS.Companion.json.parseToJsonElement(responseText).jsonObject
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

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val fileIv = Base64.getDecoder().decode(fileIvBase64)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, fileIv))
        val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
        val plaintext = cipher.doFinal(encryptedBytes)

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
        val tempAssetsDir = CTJS.Companion.assetsDir
        tempAssetsDir.mkdirs()

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
                        CTJS.Companion.json.decodeFromString<ModuleMetadata>(content)
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
        val naughtyFlags = arrayOf(
            "-javaagent", "-Xdebug", "-agentlib", "-Xrunjdwp", "-Xnoagent", "-verbose",
            "-DproxySet", "-DproxyHost", "-DproxyPort", "-Djavax.net.ssl.trustStore",
            "-Djavax.net.ssl.trustStorePassword", "-XX:+DebugNonSafepoints", "-XX:+FlightRecorder", "jdwp"
        )
        val naughtyEnv = arrayOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")

        val y3k: Unsafe by lazy {
            Unsafe::class.java.getDeclaredField("theUnsafe").let {
                it.isAccessible = true
                it[null] as Unsafe
            }
        }

        val badArg = ManagementFactory.getRuntimeMXBean().inputArguments.firstOrNull { arg ->
            naughtyFlags.any { flag -> arg.contains(flag) }
        }
        val badEnv = naughtyEnv.firstOrNull { name ->
            val value = System.getenv(name) ?: return@firstOrNull false
            naughtyFlags.any { flag -> value.contains(flag, ignoreCase = true) }
        }

        if (badArg != null || badEnv != null) {
            try {
                y3k.putAddress(0, 0)
            } catch (_: Exception) {}
            Runtime.getRuntime().exit(0)
            throw Error().also { it.stackTrace = arrayOf() }
        }
    }

    fun isLoaded(): Boolean = isLoaded
}
