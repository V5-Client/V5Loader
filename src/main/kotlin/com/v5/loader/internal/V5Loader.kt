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
import java.nio.file.Path

internal object V5Loader {
    private const val MOD_ID = "ctjs"
    private const val DEVELOPER_JAR_PREFIX = "V5-Loader-DEV-"
    private const val GITHUB_API_HOST = "api.github.com"
    private const val GITHUB_HOST = "github.com"
    private const val GITHUB_REPOSITORY = "V5-Client/V5Loader"
    private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
    private val RELEASE_TAG_REGEX = Regex("[A-Za-z0-9._-]+")
    private var initialized = false

    @JvmStatic
    @Synchronized
    fun init() {
        if (initialized) return

        val fabricLoader = FabricLoader.getInstance()
        val gameDirPath = fabricLoader.gameDir.toAbsolutePath().toString()
        System.setProperty("v5.minecraft_version", fabricLoader.rawGameVersion)
        val minecraftVersion = resolveMinecraftVersion()

        println("[V5] Using Minecraft version for loader: $minecraftVersion")
        if (FabricLoader.getInstance().isDevelopmentEnvironment) {
            println("[V5] Development environment detected; skipping self-update check.")
            initialized = true
            return
        }
        checkSelfUpdate(minecraftVersion, File(gameDirPath))
        initialized = true
    }

    @JvmStatic
    @Synchronized
    fun authenticate(): String? {
        println("[V5] Authenticating...")
        val sessionPath = sessionFile(FabricLoader.getInstance().gameDir)
        val authSession = authenticateSession(sessionPath)
        if (authSession == null || !validateAuthStatus(authSession)) {
            System.err.println("[V5] Authentication failed, expired, or account is banned.")
            return null
        }
        return authSession
    }

    private fun checkSelfUpdate(minecraftVersion: String, gameDir: File) {
        val activeJar = resolveActiveJar()
        if (activeJar.name.startsWith(DEVELOPER_JAR_PREFIX) && activeJar.extension.equals("jar", ignoreCase = true)) {
            println("[V5] Developer jar detected; skipping integrity check.")
            return
        }
        val hash = V5Crypto.calculateFileSha256(activeJar.absolutePath)
        if (hash.isEmpty()) throw IllegalStateException("[V5] Failed to hash active loader jar: ${activeJar.absolutePath}")

        val release = getLatestReleaseAsset(minecraftVersion)
        if (hash == release.expectedHash) {
            println("[V5] V5-Loader integrity verified.")
            return
        }

        stageSelfUpdate(gameDir, activeJar, release)
    }

    private fun stageSelfUpdate(
        gameDir: File,
        activeJar: File,
        release: ReleaseAsset,
    ): Nothing {
        val bytes = V5Http.httpsGetBytes(
            GITHUB_HOST,
            "/$GITHUB_REPOSITORY/releases/download/${release.tag}/${release.assetName}",
        ) ?: throw IllegalStateException("[V5] Failed to download ${release.assetName} from GitHub.")

        try {
            if (bytes.size.toLong() != release.expectedSize || V5Crypto.calculateSha256(bytes) != release.expectedHash) {
                throw IllegalStateException("[V5] GitHub workflow download failed integrity verification; refusing to install it.")
            }
            ModLoaderUpdater.stageUpdateAndRelaunch(gameDir, bytes, listOf(activeJar))
            println("[V5] V5-Loader update staged. Closing Minecraft now so the helper can swap jars.")
        } finally {
            bytes.fill(0)
        }

        Runtime.getRuntime().halt(0)
        throw IllegalStateException("Failed to terminate process after staging V5-Loader update")
    }

    private fun getLatestReleaseAsset(minecraftVersion: String): ReleaseAsset {
        val assetName = "V5-Loader-$minecraftVersion.jar"
        val release = parseJsonObject(V5Http.httpsGet(GITHUB_API_HOST, "/repos/$GITHUB_REPOSITORY/releases/latest"))
            ?: throw IllegalStateException("[V5] Failed to read the latest GitHub workflow release.")
        val tag = release.getStringOrNull("tag_name")?.takeIf(RELEASE_TAG_REGEX::matches)
            ?: throw IllegalStateException("[V5] Latest loader release has an invalid tag.")
        val asset = release.getAsJsonArray("assets")
            ?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            ?.singleOrNull { it.getStringOrNull("name") == assetName }
            ?: throw IllegalStateException("[V5] Latest GitHub workflow release does not contain $assetName.")
        val expectedHash = asset.getStringOrNull("digest")
            ?.removePrefix("sha256:")
            ?.takeIf(SHA_256_REGEX::matches)
            ?: throw IllegalStateException("[V5] GitHub did not provide a valid SHA-256 digest for $assetName.")
        val expectedSize = asset.getLongOrNull("size")?.takeIf { it > 0 }
            ?: throw IllegalStateException("[V5] GitHub did not provide a valid size for $assetName.")
        return ReleaseAsset(assetName, tag, expectedHash, expectedSize)
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

    private fun authenticateSession(sessionFilePath: Path): String? {
        refreshSession(sessionFilePath)?.let { return it }
        return runBrowserLogin(sessionFilePath)
    }

    private fun runBrowserLogin(sessionFilePath: Path): String? {
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
                if (!persistRefreshToken(sessionFilePath, callbackRefreshToken)) {
                    System.err.println("[V5] Warning: failed to persist browser refresh token.")
                }
                return refreshSession(sessionFilePath, callbackRefreshToken)
            }
        } catch (e: Exception) {
            System.err.println("[V5] Authentication callback failed: ${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            serverSocket.close()
        }
    }

    private fun validateAuthStatus(token: String): Boolean {
        val authStatus = V5Http.httpsGet(V5Http.BACKEND_HOST, "/api/authstatus", token)
        if (authStatus.isEmpty()) return false
        val authJson = parseJsonObject(authStatus) ?: return false
        val statusPayload = authJson.getAsJsonObjectOrNull("status") ?: authJson
        return !(statusPayload.getBoolOrNull("isBanned") ?: authJson.getBoolOrNull("isBanned") ?: false)
    }

    private fun resolveMinecraftVersion(): String = System.getProperty("v5.minecraft_version").orEmpty().trim()

    fun openBrowser(url: String) = openExternal(url, Desktop.Action.BROWSE) { browse(URI(url)) }

    fun openFile(file: File) = openExternal(file.absolutePath, Desktop.Action.OPEN) { open(file) }

    private fun openExternal(target: String, action: Desktop.Action, open: Desktop.() -> Unit) {
        runCatching {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop?.isSupported(action) == true) return desktop.open()
        }

        val os = System.getProperty("os.name", "").lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
            os.contains("mac") -> listOf("open", target)
            else -> listOf("xdg-open", target)
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

    private fun parseJsonObject(json: String): JsonObject? = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    private fun JsonObject.getStringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.getBoolOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.getLongOrNull(key: String): Long? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asLong }.getOrNull()

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private data class ReleaseAsset(
        val assetName: String,
        val tag: String,
        val expectedHash: String,
        val expectedSize: Long,
    )
}
