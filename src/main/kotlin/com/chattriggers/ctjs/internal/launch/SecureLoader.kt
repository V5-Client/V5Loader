package com.chattriggers.ctjs.internal.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.message.ChatLib
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.engine.module.ModuleMetadata
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object SecureLoader {
    private const val SECRET_KEY = "12345678901234567890123456789012"
    private const val AUTH_URL = "http://localhost:8080/api/loader/login"
    private const val VIRTUAL_MODULE_PREFIX = "V5"
    private const val ENTRY_POINT = "loader"

    private var USER_TOKEN = "DISCORD_TOKEN_EXAMPLE_1"

    @Volatile
    private var isLoaded = false

    fun run() {
        if (isLoaded) {
            log("&eAlready loaded, skipping...")
            return
        }

        thread(name = "SecureLoader-Auth") {
            try {
                log("&7Generating HWID...")
                val secureHWID = HWID.generateHWID()

                log("&7Authenticating...")

                val jsonBody = buildJsonObject {
                    put("token", USER_TOKEN)
                    put("hwid", secureHWID)
                }.toString()

                val connection = URL(AUTH_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "CTJS-V5")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                connection.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "No error details"
                    log("&cAuthentication failed (HTTP $responseCode): $errorStream")
                    return@thread
                }

                val responseText = connection.inputStream.bufferedReader().readText()
                val json = try {
                    CTJS.json.parseToJsonElement(responseText).jsonObject
                } catch (e: Exception) {
                    log("&cInvalid response format")
                    return@thread
                }

                if (json["success"]?.jsonPrimitive?.booleanOrNull != true) {
                    val message = json["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    log("&cAuthentication failed: $message")
                    return@thread
                }

                val payload = json["payload"]?.jsonObject
                if (payload == null) {
                    log("&cMissing payload in response")
                    return@thread
                }

                val ivStr = payload["iv"]?.jsonPrimitive?.contentOrNull
                val contentStr = payload["content"]?.jsonPrimitive?.contentOrNull

                if (ivStr == null || contentStr == null) {
                    log("&cMissing iv or content in payload")
                    return@thread
                }

                val zipBytes = decryptToBytes(contentStr, ivStr)
                processZip(zipBytes)

                isLoaded = true

            } catch (e: java.net.SocketTimeoutException) {
                log("&cConnection timed out")
            } catch (e: java.net.UnknownHostException) {
                log("&cCould not connect to server")
            } catch (e: javax.crypto.BadPaddingException) {
                log("&cDecryption failed - invalid key or corrupted data")
            } catch (e: Exception) {
                e.printStackTrace()
                log("&cError: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun log(message: String) {
        try {
            if (Client.getMinecraft().isOnThread) {
                ChatLib.chat(message)
            } else {
                Client.scheduleTask { ChatLib.chat(message) }
            }
        } catch (e: Exception) {
            println("[SecureLoader] $message")
        }
    }

    private fun processZip(zipData: ByteArray) {
        JSLoader.clearVirtualFiles()

        val zipStream = ZipInputStream(ByteArrayInputStream(zipData))

        val tempAssetsDir = File(CTJS.assetsDir, VIRTUAL_MODULE_PREFIX)
        tempAssetsDir.mkdirs()

        var fileCount = 0
        var assetCount = 0
        var rootMetadata: ModuleMetadata? = null
        val loadedDependencies = mutableSetOf<String>()

        try {
            var entry: ZipEntry? = zipStream.nextEntry

            while (entry != null) {
                try {
                    if (!entry.isDirectory) {
                        val result = processZipEntry(zipStream, entry, tempAssetsDir)
                        when (result) {
                            is ZipEntryResult.VirtualFile -> {
                                fileCount++
                                if (result.isRootMetadata) {
                                    rootMetadata = result.metadata
                                }
                            }
                            is ZipEntryResult.AssetFile -> assetCount++
                            is ZipEntryResult.Skipped -> { /* ignore */ }
                        }
                    }
                } catch (e: Exception) {
                    log("&eWarning: Failed to process ${entry.name}: ${e.message}")
                } finally {
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        } finally {
            zipStream.close()
        }

        log("&aLoaded $fileCount virtual files, $assetCount assets")

        rootMetadata?.requires?.forEach { dependency ->
            if (dependency.isNotBlank() && dependency !in loadedDependencies) {
                try {
                    log("&7Installing dependency: $dependency")
                    ModuleManager.importModule(dependency, VIRTUAL_MODULE_PREFIX)
                    loadedDependencies.add(dependency)
                } catch (e: Exception) {
                    log("&cFailed to install dependency '$dependency': ${e.message}")
                }
            }
        }

        Client.scheduleTask {
            try {
                val entryPath = "$VIRTUAL_MODULE_PREFIX/$ENTRY_POINT"
                if (JSLoader.hasVirtualFile(entryPath) ||
                    JSLoader.hasVirtualFile("$entryPath.js")) {
                    JSLoader.loadVirtualModule(entryPath)
                } else {
                    log("&cEntry point not found: $entryPath")
                    val availableFiles = JSLoader.getVirtualFilePaths()
                        .filter { it.endsWith(".js") }
                        .take(10)
                    if (availableFiles.isNotEmpty()) {
                        log("&7Available JS files: ${availableFiles.joinToString(", ")}")
                    }
                }
            } catch (e: Exception) {
                log("&cFailed to load entry point: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private sealed class ZipEntryResult {
        data class VirtualFile(val isRootMetadata: Boolean, val metadata: ModuleMetadata?) : ZipEntryResult()
        object AssetFile : ZipEntryResult()
        object Skipped : ZipEntryResult()
    }

    private fun processZipEntry(
        zipStream: ZipInputStream,
        entry: ZipEntry,
        assetsDir: File
    ): ZipEntryResult {
        val rawName = entry.name

        var entryName = rawName
            .replace('\\', '/')
            .removePrefix("$VIRTUAL_MODULE_PREFIX/")
            .removePrefix("/")
            .trim()

        if (entryName.isEmpty() ||
            entryName.startsWith(".") ||
            entryName.contains("/.") ||
            entryName.endsWith(".yml") ||
            entryName.endsWith(".md") ||
            entryName == "desktop.ini" ||
            entryName.startsWith(".github/") ||
            entryName.startsWith(".vscode/")) {
            return ZipEntryResult.Skipped
        }

        val virtualPath = "$VIRTUAL_MODULE_PREFIX/$entryName"
        val extension = entryName.substringAfterLast('.', "").lowercase()

        return when {
            extension == "js" || extension == "json" -> {
                val content = String(zipStream.readAllBytes(), StandardCharsets.UTF_8)
                JSLoader.addVirtualFile(virtualPath, content)

                val isRootMetadata = entryName == "metadata.json"
                var metadata: ModuleMetadata? = null

                if (isRootMetadata) {
                    metadata = try {
                        CTJS.json.decodeFromString<ModuleMetadata>(content)
                    } catch (e: Exception) {
                        log("&eWarning: Failed to parse metadata.json: ${e.message}")
                        null
                    }
                }

                ZipEntryResult.VirtualFile(isRootMetadata, metadata)
            }

            extension in listOf("png", "jpg", "jpeg", "gif", "svg", "wav", "ogg", "mp3") -> {
                val assetFile = File(assetsDir, entryName)
                assetFile.parentFile?.mkdirs()

                FileOutputStream(assetFile).use { fos ->
                    zipStream.copyTo(fos)
                }

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
        JSLoader.clearVirtualFiles()
        run()
    }

    fun isLoaded(): Boolean = isLoaded
}