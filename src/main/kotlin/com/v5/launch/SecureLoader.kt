package com.v5.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.engine.module.ModuleMetadata
import com.v5.launch.HWID
import com.v5.api.V5Auth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sun.misc.Unsafe
import java.awt.Desktop
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
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
import java.util.Locale
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
    private const val HEARTBEAT_INTERVAL_MS = 150_000L // 2 minutes 30 seconds
    private const val DOWNLOAD_KDF_INFO = "v5-download-kek-v2"
    private val rng = SecureRandom()

    @Volatile private var sessionReleaseChannel: String? = null
    @Volatile private var isPluginLoaded = false
    @Volatile private var areMixinsApplied = false
    @Volatile private var isLoaded = false
    @Volatile private var rootMetadata: ModuleMetadata? = null

    private var heartbeatThread: Thread? = null
    private val cachedHwid by lazy { HWID.generateHWID() }

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
            ensureAuthenticatedSession()
            val token = V5Auth.internalToken ?: shutDownHard()
            val releaseChannel = sessionReleaseChannel ?: shutDownHard()

            if (releaseChannel == "Dev") {
                println("[V5] Hi Dev! Skipping loader step.")
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

    fun onCTMixinApplication() {
        if (areMixinsApplied) return
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
        val metadata = rootMetadata ?: return

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
                if (JSLoader.hasVirtualFile(entryPath) || JSLoader.hasVirtualFile("$entryPath.js")
                ) {
                    JSLoader.loadVirtualModule(entryPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        isLoaded = true
        startHeartbeat()
    }

    private fun ensureAuthenticatedSession() {
        if (V5Auth.internalToken != null && sessionReleaseChannel != null) return
        if (tryHwidLogin()) {
            println("[V5] Auto-login successful!")
            return
        }

        println("[V5] Auto-login failed. Game loading paused for authentication.")
        authenticate()

        val token = V5Auth.internalToken ?: shutDownHard()
        sessionReleaseChannel = fetchAndValidateReleaseChannel(token)

        println("[V5] Authenticated.")
        bindHwid(token)
        println("[V5] Welcome to V5!")
    }

    private fun readResponseText(connection: HttpURLConnection): String {
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseJsonObjectOrNull(text: String): JsonObject? {
        if (text.isBlank()) return null
        return try {
            CTJS.Companion.json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun tryHwidLogin(): Boolean {
        try {
            val url = URL("$BACKEND_URL/api/auth/login-hwid")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "V5Loader/1.1")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
            }

            val jsonBody = "{\"hwid\": \"$cachedHwid\"}"
            connection.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            val responseText = readResponseText(connection)
            val json = parseJsonObjectOrNull(responseText)
            val backendError = json?.get("error")?.jsonPrimitive?.contentOrNull
                ?: json?.get("reason")?.jsonPrimitive?.contentOrNull

            if (code == 200) {
                if (json?.get("success")?.jsonPrimitive?.booleanOrNull == true) {
                    V5Auth.internalToken = json["token"]?.jsonPrimitive?.contentOrNull
                    sessionReleaseChannel = json["channel"]?.jsonPrimitive?.contentOrNull
                    return V5Auth.internalToken != null && sessionReleaseChannel != null
                }
                return false
            }

            when (backendError) {
                "HWID_NOT_FOUND" -> return false // user not bound yet
                "HWID_CONFLICT" -> {
                    println("[V5] This HWID is linked to multiple accounts. Contact support.")
                    shutDownHard()
                }
                "BANNED" -> {
                    println("[V5] Access denied: you are banned.")
                    shutDownHard()
                }
                "ACCESS_DENIED" -> {
                    println("[V5] Access denied: account has no V5 access.")
                    shutDownHard()
                }
                else -> {
                    println("[V5] Auto-login failed (code=$code, error=${backendError ?: "unknown"}).")
                    return false
                }
            }
        } catch (e: Exception) {
            println("[V5] Auto-login error: ${e.message}")
        }
        return false
    }


    private fun bindHwid(token: String) {
        try {
            val url = URL("$BACKEND_URL/api/auth/bind-hwid")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "V5Loader/1.1")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
            }

            val jsonBody = "{\"hwid\": \"$cachedHwid\"}"
            connection.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            if (code in 200..299) return

            val responseText = readResponseText(connection)
            val json = parseJsonObjectOrNull(responseText)
            val backendError = json?.get("error")?.jsonPrimitive?.contentOrNull

            when (backendError) {
                "HWID_ALREADY_BOUND" -> {
                    println("[V5] This HWID is already bound to a different account.")
                    shutDownHard()
                }
                "BANNED" -> {
                    println("[V5] Access denied: banned account.")
                    shutDownHard()
                }
                "UNAUTHORIZED" -> {
                    println("[V5] Session expired before HWID bind.")
                    shutDownHard()
                }
                else -> {
                    println("[V5] Failed to bind HWID (code=$code, error=${backendError ?: "unknown"}).")
                    shutDownHard()
                }
            }
        } catch (e: Exception) {
            println("[V5] Failed to bind HWID: ${e.message}")
            shutDownHard()
        }
    }

    private fun authenticate() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        serverSocket.soTimeout = 240 * 1000 // 240 seconds

        val authUrl = "$BACKEND_URL/api/auth/discord/login?state=port:$port"
        println("[V5] Opening browser to: $authUrl")

        if (!openBrowserUrl(authUrl)) {
            println("[V5] FAILED TO OPEN BROWSER! Open this URL manually:")
            println(authUrl)
        }

        println("[V5] Waiting for authentication...")

        try {
            val clientSocket = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val requestLine = reader.readLine() // GET /callback?token=... HTTP/1.1

            val writer = PrintWriter(clientSocket.getOutputStream(), true)

            if (requestLine != null && requestLine.contains("token=")) {
                val tokenStart = requestLine.indexOf("token=") + 6
                val tokenEnd = requestLine.indexOf(" ", tokenStart)
                val rawToken =
                    if (tokenEnd == -1) requestLine.substring(tokenStart)
                    else requestLine.substring(tokenStart, tokenEnd)
                V5Auth.internalToken = rawToken.split("&")[0]

                writer.println("HTTP/1.1 200 OK")
                writer.println("Content-Type: text/html")
                writer.println("\r\n")
                writer.println(
                    "<h1>Authenticated!</h1><p>You can close this tab and return to the game.</p><script>window.close()</script>"
                )
            } else if (requestLine != null && requestLine.contains("error=")) {
                val errorStart = requestLine.indexOf("error=") + 6
                val errorEnd = requestLine.indexOf(" ", errorStart)
                val error =
                    if (errorEnd == -1) requestLine.substring(errorStart)
                    else requestLine.substring(errorStart, errorEnd)

                writer.println("HTTP/1.1 403 Forbidden")
                writer.println("Content-Type: text/html")
                writer.println("\r\n")

                when (error) {
                    "access_denied" -> {
                        writer.println("<h1>Access Denied</h1><p>You do not have access to V5.</p>")
                        println("[V5] Access denied: user does not have access to V5")
                    }
                    "banned" -> {
                        writer.println("<h1>Access Denied</h1><p>You are banned.</p>")
                        println("[V5] Access denied: user is banned")
                    }
                    else -> {
                        writer.println("<h1>Authentication failed</h1>")
                        println("[V5] Authentication failed with error: $error")
                    }
                }
                shutDownHard()
            } else {
                writer.println("HTTP/1.1 400 Bad Request")
                writer.println("Content-Type: text/html")
                writer.println("\r\n")
                writer.println("<h1>Authentication failed</h1>")
            }

            writer.close()
            clientSocket.close()
            serverSocket.close()
        } catch (e: Exception) {
            println("[V5] Authentication timed out or failed.")
            shutDownHard()
        }
    }

    private fun openBrowserUrl(url: String): Boolean {
        val osName = System.getProperty("os.name", "").lowercase(Locale.getDefault())
        val isMac = osName.contains("mac")
        val isWindows = osName.contains("win")
        val isLinux =
            osName.contains("nix") ||
                    osName.contains("nux") ||
                    osName.contains("linux") ||
                    osName.contains("aix")

        if (isMac) {
            try {
                Runtime.getRuntime().exec(arrayOf("open", url))
                return true
            } catch (_: Exception) {}
        }

        try {
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                Desktop.getDesktop().browse(URI(url))
                return true
            }
        } catch (_: Exception) {}

        return try {
            when {
                isWindows -> {
                    Runtime.getRuntime()
                        .exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
                    true
                }
                isLinux -> {
                    Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchAndValidateReleaseChannel(token: String): String {
        try {
            val statusUrl = URL("$BACKEND_URL/api/authstatus")
            val statusConnection = statusUrl.openConnection()
            statusConnection.setRequestProperty("Authorization", "Bearer $token")
            statusConnection.setRequestProperty("User-Agent", "V5Loader/1.0")
            statusConnection.connectTimeout = 10000
            statusConnection.readTimeout = 30000

            val statusCode = (statusConnection as HttpURLConnection).responseCode
            if (statusCode != 200) {
                println("[V5] Auth status check failed with code: $statusCode")
                shutDownHard()
            }

            val statusText = statusConnection.inputStream.bufferedReader().readText()
            val statusJson = CTJS.Companion.json.parseToJsonElement(statusText).jsonObject
            val userStatus = statusJson["status"]?.jsonObject

            if (userStatus != null) {
                val isBanned = userStatus["isBanned"]?.jsonPrimitive?.booleanOrNull ?: false
                val releaseChannel =
                    userStatus["releaseChannel"]?.jsonPrimitive?.contentOrNull ?: "No access"

                if (isBanned) {
                    println("[V5] Download failed: You are banned.")
                    shutDownHard()
                }
                if (releaseChannel == "No access") {
                    println("[V5] Download failed: You do not have access to V5.")
                    shutDownHard()
                }
                return releaseChannel
            }
        } catch (e: Exception) {
            println("[V5] Failed to check auth status: ${e.message}")
            shutDownHard()
        }
        shutDownHard()
    }

    private fun startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread!!.isAlive) return

        heartbeatThread =
            thread(start = true, isDaemon = true, name = "V5-Heartbeat") {
                while (isLoaded) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS)
                        performHeartbeat()
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                    }
                }
            }
    }

    private fun performHeartbeat() {
        val currentToken = V5Auth.internalToken ?: return

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
                val newToken = json["token"]?.jsonPrimitive?.contentOrNull

                if (newToken != null) {
                    V5Auth.internalToken = newToken
                }
            } else if (responseCode == 401 || responseCode == 403) {
                println("[V5] Session expired or revoked. Exiting.")
                shutDownHard()
            } else {
                println("[V5] Heartbeat failed with code: $responseCode")
            }
        } catch (e: Exception) {
            println("[V5] Failed to send heartbeat: ${e.message}")
        }
    }

    private fun downloadZip(token: String): ByteArray {
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
        if (responseCode != 200) {
            val responseText = readResponseText(connection)
            val json =
                try {
                    CTJS.Companion.json.parseToJsonElement(responseText).jsonObject
                } catch (e: Exception) {
                    null
                }

            val errorMessage = json?.get("error")?.jsonPrimitive?.contentOrNull ?: "Unknown error"

            when (errorMessage) {
                "BANNED" -> println("[V5] Download failed: You are banned.")
                "ACCESS_DENIED" -> println("[V5] Download failed: You do not have access to V5.")
                "UNAUTHORIZED" ->
                    println("[V5] Download failed: Unauthorized. Please authenticate again.")
                "INVALID_CHANNEL" -> println("[V5] Download failed: Invalid release channel.")
                "FILE_NOT_FOUND" -> println("[V5] Download failed: Build not found.")
                else ->
                    println(
                        "[V5] Download failed with error: $errorMessage (code: $responseCode)"
                    )
            }
            shutDownHard()
        }

        val responseText = connection.inputStream.bufferedReader().readText()
        val json = CTJS.Companion.json.parseToJsonElement(responseText).jsonObject

        if (json["success"]?.jsonPrimitive?.booleanOrNull != true) {
            shutDownHard()
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
            version != "2" ||
            serverPub == null ||
            serverNonce == null ||
            kdfSalt == null ||
            wrapIv == null ||
            wrappedKey == null ||
            fileIv == null ||
            contentStr == null
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
            X509EncodedKeySpec(
                Base64.getDecoder().decode(serverPublicKeyBase64)
            )
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
                    // file errors, probably need to report to devs? maybe we could have error
                    // webhook?
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
        data class VirtualFile(val isRootMetadata: Boolean, val metadata: ModuleMetadata?) :
            ZipEntryResult()
        object AssetFile : ZipEntryResult()
        object Skipped : ZipEntryResult()
    }

    private fun processZipEntry(
        zipStream: ZipInputStream,
        entry: ZipEntry,
        assetsDir: File
    ): ZipEntryResult {
        val rawName = entry.name
        val entryName =
            rawName.replace('\\', '/')
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
                    metadata =
                        try {
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
            "-javaagent",
            "-Xdebug",
            "-agentlib",
            "-Xrunjdwp",
            "-Xnoagent",
            "-verbose",
            "-DproxySet",
            "-DproxyHost",
            "-DproxyPort",
            "-Djavax.net.ssl.trustStore",
            "-Djavax.net.ssl.trustStorePassword",
            "-XX:+DebugNonSafepoints",
            "-XX:+FlightRecorder",
            "jdwp"
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
            } catch (_: Exception) {
            }
            Runtime.getRuntime().exit(0)
            throw Error().also { it.stackTrace = arrayOf() }
        }
    }

    fun isLoaded(): Boolean = isLoaded
}