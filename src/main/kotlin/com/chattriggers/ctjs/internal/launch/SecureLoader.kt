package com.chattriggers.ctjs.internal.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.engine.module.ModuleMetadata
import java.awt.Desktop
import java.io.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SecureLoader {
    private const val SECRET_KEY = "12345678901234567890123456789012"
    private const val BACKEND_URL = "https://backend.rdbt.top"
    private const val VIRTUAL_MODULE_PREFIX = "V5"
    private const val ENTRY_POINT = "loader"
    private const val HEARTBEAT_INTERVAL_MS = 150_000L // 2 minutes 30 seconds

    @Volatile private var jwtToken: String? = null
    @Volatile private var sessionReleaseChannel: String? = null
    @Volatile private var isPluginLoaded = false
    @Volatile private var areMixinsApplied = false
    @Volatile private var isLoaded = false
    @Volatile private var rootMetadata: ModuleMetadata? = null

    private var heartbeatThread: Thread? = null

    fun run() {
        onMixinPlugin()
        onCTMixinApplication()
        onInitalise()
    }

    fun onMixinPlugin() {
        if (isPluginLoaded) return
        println("[V5] Stage: onMixinPlugin")
        try {
            ensureAuthenticatedSession()
            val token = jwtToken ?: exitProcess(0)
            val releaseChannel = sessionReleaseChannel ?: exitProcess(0)

            if (releaseChannel == "Dev") {
                println("[V5] Hi Dev! Skipping loader step.")
                isPluginLoaded = true
                return
            }

            val zipBytes = downloadZip(token)
            processZip(zipBytes)
            isPluginLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
            exitProcess(0)
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

    fun onInitalise() {
        if (isLoaded) return
        println("[V5] Stage: onInitalise")
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
        if (jwtToken != null && sessionReleaseChannel != null) return
        if (jwtToken != null && sessionReleaseChannel == null) {
            sessionReleaseChannel = fetchAndValidateReleaseChannel(jwtToken!!)
            return
        }

        println("[V5] Game loading paused for authentication.")
        authenticate()

        val token = jwtToken ?: exitProcess(0)
        sessionReleaseChannel = fetchAndValidateReleaseChannel(token)
        println("[V5] Authenticated. Welcome to V5!")
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
                jwtToken = rawToken.split("&")[0]

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
                exitProcess(0)
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
            exitProcess(0)
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
                exitProcess(0)
            }

            val statusText = statusConnection.inputStream.bufferedReader().readText()
            val statusJson = CTJS.json.parseToJsonElement(statusText).jsonObject
            val userStatus = statusJson["status"]?.jsonObject

            if (userStatus != null) {
                val isBanned = userStatus["isBanned"]?.jsonPrimitive?.booleanOrNull ?: false
                val releaseChannel =
                        userStatus["releaseChannel"]?.jsonPrimitive?.contentOrNull ?: "No access"

                if (isBanned) {
                    println("[V5] Download failed: You are banned.")
                    exitProcess(0)
                }
                if (releaseChannel == "No access") {
                    println("[V5] Download failed: You do not have access to V5.")
                    exitProcess(0)
                }
                return releaseChannel
            }
        } catch (e: Exception) {
            println("[V5] Failed to check auth status: ${e.message}")
            exitProcess(0)
        }
        exitProcess(0)
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
                        } catch (e: Exception) {}
                    }
                }
    }

    private fun performHeartbeat() {
        val currentToken = jwtToken ?: return

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
                val json = CTJS.json.parseToJsonElement(responseText).jsonObject
                val newToken = json["token"]?.jsonPrimitive?.contentOrNull

                if (newToken != null) {
                    jwtToken = newToken
                }
            } else if (responseCode == 401 || responseCode == 403) {
                println("[V5] Session expired or revoked. Exiting.")
                exitProcess(0)
            } else {
                println("[V5] Heartbeat failed with code: $responseCode")
            }
        } catch (e: Exception) {
            println("[V5] Failed to send heartbeat: ${e.message}")
        }
    }

    private fun downloadZip(token: String): ByteArray {
        val url = URL("$BACKEND_URL/api/download/v5")
        val connection = url.openConnection()
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("User-Agent", "V5Loader/1.0")
        connection.connectTimeout = 10000
        connection.readTimeout = 30000

        val responseCode = (connection as HttpURLConnection).responseCode
        if (responseCode != 200) {
            val responseText = connection.inputStream.bufferedReader().readText()
            val json =
                    try {
                        CTJS.json.parseToJsonElement(responseText).jsonObject
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
            exitProcess(0)
        }

        val responseText = connection.inputStream.bufferedReader().readText()
        val json = CTJS.json.parseToJsonElement(responseText).jsonObject

        if (json["success"]?.jsonPrimitive?.booleanOrNull != true) {
            exitProcess(0)
        }

        val payload = json["payload"]?.jsonObject ?: throw IOException("Missing payload")
        val ivStr = payload["iv"]?.jsonPrimitive?.contentOrNull
        val contentStr = payload["content"]?.jsonPrimitive?.contentOrNull

        if (ivStr == null || contentStr == null) {
            throw IOException("Invalid payload structure")
        }

        return decryptToBytes(contentStr, ivStr)
    }

    private fun processZip(zipData: ByteArray) {
        JSLoader.clearVirtualFiles()

        val zipStream = ZipInputStream(ByteArrayInputStream(zipData))
        val tempAssetsDir = File(CTJS.assetsDir, VIRTUAL_MODULE_PREFIX)
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

        return when {
            extension == "js" || extension == "json" -> {
                val content = String(zipStream.readAllBytes(), StandardCharsets.UTF_8)
                JSLoader.addVirtualFile(virtualPath, content)
                val isRootMetadata = entryName == "metadata.json"
                var metadata: ModuleMetadata? = null
                if (isRootMetadata) {
                    metadata =
                            try {
                                CTJS.json.decodeFromString<ModuleMetadata>(content)
                            } catch (e: Exception) {
                                null
                            }
                }
                ZipEntryResult.VirtualFile(isRootMetadata, metadata)
            }
            extension in listOf("png", "jpg", "jpeg", "gif", "svg", "wav", "ogg", "mp3") -> {
                val assetFile = File(assetsDir, entryName)
                assetFile.parentFile?.mkdirs()
                FileOutputStream(assetFile).use { fos -> zipStream.copyTo(fos) }
                ZipEntryResult.AssetFile
            }
            else -> ZipEntryResult.Skipped
        }
    }

    private fun decryptToBytes(encryptedBase64: String, ivBase64: String): ByteArray {
        val keyBytes = SECRET_KEY.toByteArray(StandardCharsets.UTF_8)
        val key = SecretKeySpec(keyBytes, "AES")
        val ivBytes = Base64.getDecoder().decode(ivBase64)
        val iv = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
        return cipher.doFinal(encryptedBytes)
    }

    fun reload() {
        isLoaded = false
        isPluginLoaded = false
        areMixinsApplied = false
        heartbeatThread = null
        JSLoader.clearVirtualFiles()
        run()
    }

    fun isLoaded(): Boolean = isLoaded

    fun getJwtToken(): String? = jwtToken
}
