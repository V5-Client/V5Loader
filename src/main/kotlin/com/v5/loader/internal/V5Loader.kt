package com.v5.loader.internal

import com.chattriggers.ctjs.internal.launch.ModLoaderUpdater
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.awt.Desktop
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal object V5Loader {
    private const val MOD_ID = "ctjs"
    private const val LEGACY_MOD_LOADER_FILE_NAME = "V5ModLoader.jar"
    private val secretLock = Any()
    private var initialized = false
    private var sessionToken = ""

    @JvmStatic
    @Synchronized
    fun init() {
        if (initialized) return

        val gameDirPath = resolveGameDirPath()
        val sessionFilePath = buildSessionFilePath(gameDirPath)
        val minecraftVersion = resolveMinecraftVersion()

        println("[V5] Using Minecraft version for loader: $minecraftVersion")
        println("[V5] Authenticating...")

        val authSession = authenticate(sessionFilePath)
        if (authSession == null || !validateAuthStatus(authSession.accessToken)) {
            throw IllegalStateException("[V5] Authentication failed, expired, or account is banned.")
        }

        synchronized(secretLock) { sessionToken = authSession.accessToken }
        if (FabricLoader.getInstance().isDevelopmentEnvironment) {
            println("[V5] Development environment detected; skipping self-update check.")
            initialized = true
            return
        }
        checkSelfUpdate(authSession.accessToken, minecraftVersion, File(gameDirPath))
        initialized = true
    }

    @JvmStatic
    fun consumeToken(): String {
        return synchronized(secretLock) {
            val current = sessionToken
            sessionToken = ""
            current
        }
    }

    private fun checkSelfUpdate(token: String, minecraftVersion: String, gameDir: File) {
        val activeJar = resolveActiveJar()
        val legacyModLoader = File(gameDir, "mods/$LEGACY_MOD_LOADER_FILE_NAME").takeIf(File::isFile)
        val hash = V5Crypto.calculateFileSha256(activeJar.absolutePath)
        if (hash.isEmpty()) throw IllegalStateException("[V5] Failed to hash active loader jar: ${activeJar.absolutePath}")

        val response = V5Http.fetchLoaderHash(token, hash, minecraftVersion)
        val integrity = parseJsonObject(response)?.getStringOrNull("integrity")?.lowercase()
            ?: throw IllegalStateException("[V5] Loader integrity check failed.")

        when (integrity) {
            "valid" -> if (legacyModLoader == null) {
                println("[V5] V5-Loader integrity verified.")
            } else {
                stageSelfUpdate(token, minecraftVersion, gameDir, activeJar, legacyModLoader)
            }
            "outdated" -> stageSelfUpdate(token, minecraftVersion, gameDir, activeJar, legacyModLoader)
            "invalid" -> throw IllegalStateException("[V5] V5-Loader integrity is invalid; refusing to run a modified jar.")
            else -> throw IllegalStateException("[V5] Unknown V5-Loader integrity state: $integrity")
        }
    }

    private fun stageSelfUpdate(
        token: String,
        minecraftVersion: String,
        gameDir: File,
        activeJar: File,
        legacyModLoader: File?
    ): Nothing {
        val bytes = V5Http.httpsGetBytes(
            V5Http.BACKEND_HOST,
            "/api/download/loader?minecraft_version=$minecraftVersion",
            token,
        ) ?: throw IllegalStateException("[V5] Failed to download updated V5-Loader.jar.")

        try {
            ModLoaderUpdater.stageUpdateAndRelaunch(gameDir, bytes, listOfNotNull(activeJar, legacyModLoader))
            println("[V5] V5-Loader update staged. Closing Minecraft now so the helper can swap jars.")
        } finally {
            bytes.fill(0)
        }

        Runtime.getRuntime().halt(0)
        throw IllegalStateException("Failed to terminate process after staging V5-Loader update")
    }

    private fun resolveActiveJar(): File {
        val container = FabricLoader.getInstance().getModContainer(MOD_ID)
            .orElseThrow { IllegalStateException("[V5] Could not resolve active $MOD_ID mod container.") }
        return container.rootPaths
            .mapNotNull(::resolveJarFile)
            .firstOrNull()
            ?: throw IllegalStateException("[V5] Active $MOD_ID container is not a jar; self-update is disabled in development.")
    }

    private fun resolveJarFile(path: Path): File? {
        runCatching { path.toAbsolutePath().normalize().toFile() }.getOrNull()?.let { file ->
            if (file.isFile && file.extension.equals("jar", ignoreCase = true)) return file
        }

        val uri = path.toUri().toString()
        if (!uri.startsWith("jar:")) return null
        val bang = uri.indexOf('!')
        if (bang < 0) return null
        return runCatching { File(URI(uri.substring(4, bang))) }
            .getOrNull()
            ?.takeIf { it.isFile && it.extension.equals("jar", ignoreCase = true) }
    }

    private fun authenticate(sessionFilePath: String): AuthSession? {
        val storedRefreshToken = readRefreshTokenFromSessionFile(sessionFilePath)
        if (storedRefreshToken.isNotEmpty()) {
            exchangeRefreshToken(storedRefreshToken, sessionFilePath)?.let { return it }
        }
        return runBrowserLogin(sessionFilePath)
    }

    private fun runBrowserLogin(sessionFilePath: String): AuthSession? {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket.soTimeout = 240_000

        try {
            val port = serverSocket.localPort
            val expectedLocalNonce = V5Crypto.generateUrlSafeNonce(16)
            val authUrl = "https://${V5Http.BACKEND_HOST}/api/auth/discord/login?state=port:$port:$expectedLocalNonce"
            println("[V5] Opening browser for authentication: $authUrl")
            openBrowser(authUrl)

            serverSocket.accept().use { clientSocket ->
                if (!clientSocket.inetAddress.isLoopbackAddress) return null
                val requestLine = BufferedReader(InputStreamReader(clientSocket.getInputStream())).readLine() ?: return null
                val error = extractQueryParam(requestLine, "error=")
                val callbackNonce = extractQueryParam(requestLine, "nonce=")
                if (callbackNonce != expectedLocalNonce) return null
                if (error.isNotEmpty()) {
                    writeHttpResponse(clientSocket, "HTTP/1.1 403 Forbidden", "<h1>Access Denied</h1><p>$error</p>")
                    return null
                }

                writeHttpResponse(
                    clientSocket,
                    "HTTP/1.1 200 OK",
                    "<h1>Authenticated!</h1><p>You can close this tab and return to the game.</p><script>window.close()</script>",
                )

                val callbackRefreshToken = extractQueryParam(requestLine, "refresh_token=")
                if (callbackRefreshToken.isEmpty()) return null
                if (!writeRefreshTokenToSessionFile(sessionFilePath, callbackRefreshToken)) {
                    System.err.println("[V5] Warning: failed to persist browser refresh token.")
                }
                return exchangeRefreshToken(callbackRefreshToken, sessionFilePath)
            }
        } catch (e: Exception) {
            System.err.println("[V5] Authentication callback failed: ${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            serverSocket.close()
        }
    }

    private fun exchangeRefreshToken(refreshToken: String, sessionFilePath: String): AuthSession? {
        val response = V5Http.httpsPost(
            V5Http.BACKEND_HOST,
            "/api/auth/refresh",
            """{"refresh_token":"${jsonEscape(refreshToken)}"}""",
        )
        if (response.isEmpty()) return null
        val json = parseJsonObject(response) ?: return null
        val accessToken = json.getStringOrNull("access_token").orEmpty()
        val rotatedRefresh = json.getStringOrNull("refresh_token").orEmpty()
        val error = json.getStringOrNull("error").orEmpty()
        if (accessToken.isEmpty() || rotatedRefresh.isEmpty()) {
            if (error in REVOKED_REFRESH_ERRORS) secureDeleteFile(sessionFilePath)
            return null
        }
        writeRefreshTokenToSessionFile(sessionFilePath, rotatedRefresh)
        return AuthSession(accessToken)
    }

    private fun validateAuthStatus(token: String): Boolean {
        val authStatus = V5Http.httpsGet(V5Http.BACKEND_HOST, "/api/authstatus", token)
        if (authStatus.isEmpty()) return false
        val authJson = parseJsonObject(authStatus) ?: return false
        val statusPayload = authJson.getAsJsonObjectOrNull("status") ?: authJson
        return !(statusPayload.getBoolOrNull("isBanned") ?: authJson.getBoolOrNull("isBanned") ?: false)
    }

    private fun resolveMinecraftVersion(): String = System.getProperty("v5.minecraft_version").orEmpty().trim()

    private fun resolveGameDirPath(): String {
        System.getProperty("v5.game_dir").orEmpty().trim().ifEmpty {
            System.getProperty("user.dir").orEmpty().trim()
        }.ifEmpty {
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) System.getenv("APPDATA").orEmpty().let { if (it.isBlank()) "" else Path.of(it, ".minecraft").toString() }
            else System.getenv("HOME").orEmpty().let { if (it.isBlank()) "" else Path.of(it, ".minecraft").toString() }
        }.let { return it }
    }

    private fun buildSessionFilePath(gameDir: String): String =
        if (gameDir.isEmpty()) "" else Path.of(gameDir, ".v5", "session.json").toString()

    private fun readRefreshTokenFromSessionFile(sessionFilePath: String): String {
        if (sessionFilePath.isEmpty()) return ""
        val path = Path.of(sessionFilePath)
        if (!path.isRegularFile()) return ""
        return parseJsonObject(path.readText())?.getStringOrNull("refresh_token").orEmpty().trim()
    }

    private fun writeRefreshTokenToSessionFile(sessionFilePath: String, refreshToken: String): Boolean {
        if (sessionFilePath.isEmpty() || refreshToken.isEmpty()) return false
        val filePath = Path.of(sessionFilePath)
        runCatching { Files.createDirectories(filePath.parent) }
        val content = """{"refresh_token":"${jsonEscape(refreshToken)}","updated_at":${System.currentTimeMillis() / 1000}}"""
        return try {
            filePath.writeText(content)
            if (!System.getProperty("os.name", "").lowercase().contains("win")) {
                Files.setPosixFilePermissions(filePath, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun secureDeleteFile(path: String) {
        if (path.isNotEmpty()) runCatching { Files.deleteIfExists(Path.of(path)) }
    }

    private fun openBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        val os = System.getProperty("os.name", "").lowercase()
        val command = when {
            os.contains("win") -> listOf("cmd", "/c", "start", "", url)
            os.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        ProcessBuilder(command).start()
    }

    private fun writeHttpResponse(clientSocket: java.net.Socket, statusLine: String, body: String) {
        clientSocket.getOutputStream().write("$statusLine\r\nContent-Type: text/html\r\n\r\n$body".toByteArray(StandardCharsets.UTF_8))
    }

    private fun extractQueryParam(requestLine: String, key: String): String {
        val start = requestLine.indexOf(key)
        if (start < 0) return ""
        val valueStart = start + key.length
        val end = listOf(requestLine.indexOf('&', valueStart), requestLine.indexOf(' ', valueStart))
            .filter { it >= 0 }
            .minOrNull() ?: requestLine.length
        return java.net.URLDecoder.decode(requestLine.substring(valueStart, end), StandardCharsets.UTF_8)
    }

    private fun jsonEscape(input: String): String = buildString(input.length + 8) {
        input.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
    }

    private fun parseJsonObject(json: String): JsonObject? = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    private fun JsonObject.getStringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.getBoolOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private data class AuthSession(val accessToken: String)

    private val REVOKED_REFRESH_ERRORS = setOf(
        "INVALID_REFRESH_TOKEN",
        "REFRESH_TOKEN_EXPIRED",
        "REFRESH_TOKEN_REUSED",
        "SESSION_REVOKED",
    )
}
