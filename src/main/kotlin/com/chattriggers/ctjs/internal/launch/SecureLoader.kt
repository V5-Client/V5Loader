package com.chattriggers.ctjs.internal.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.message.ChatLib
import com.chattriggers.ctjs.internal.engine.JSLoader
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object SecureLoader {
    private const val SECRET_KEY = "12345678901234567890123456789012"
    private const val AUTH_URL = "http://localhost:8080/api/loader/login"
    private var USER_TOKEN = "DISCORD_TOKEN_EXAMPLE_1"

    fun run() {
        thread {
            try {
                log("&7[Loader] Authenticating...")

                val jsonBody = buildJsonObject {
                    put("token", USER_TOKEN)
                    put("hwid", System.getProperty("user.name"))
                }.toString()

                val connection = URL(AUTH_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "CTJS-V5")
                connection.doOutput = true

                connection.outputStream.use { it.write(jsonBody.toByteArray()) }

                if (connection.responseCode != 200) {
                    log("&c[Loader] Failed: ${connection.responseCode}")
                    return@thread
                }

                val responseText = connection.inputStream.bufferedReader().readText()
                val json = CTJS.json.parseToJsonElement(responseText).jsonObject

                if (json["success"]?.jsonPrimitive?.boolean != true) return@thread

                val payload = json["payload"]?.jsonObject!!
                val ivStr = payload["iv"]?.jsonPrimitive?.content!!
                val contentStr = payload["content"]?.jsonPrimitive?.content!!

                val zipBytes = decryptToBytes(contentStr, ivStr)
                processZip(zipBytes)

            } catch (e: Exception) {
                e.printStackTrace()
                log("&c[Loader] Error: ${e.message}")
            }
        }
    }

    private fun log(message: String) {
        if (Client.getMinecraft().isOnThread) {
            ChatLib.chat(message)
        } else {
            Client.scheduleTask { ChatLib.chat(message) }
        }
    }

    private fun processZip(zipData: ByteArray) {
        val zipStream = ZipInputStream(ByteArrayInputStream(zipData))
        var entry = zipStream.nextEntry

        val tempAssetsDir = File(CTJS.assetsDir, "V5")
        tempAssetsDir.mkdirs()

        val rootPrefix = "V5/"
        var fileCount = 0

        while (entry != null) {
            if (!entry.isDirectory) {
                val entryName = entry.name.replace("\\", "/")
                val virtualPath = rootPrefix + entryName

                if (entryName.endsWith(".js") || entryName.endsWith(".json")) {
                    val content = String(zipStream.readAllBytes(), StandardCharsets.UTF_8)
                    JSLoader.virtualFiles[virtualPath] = content
                    fileCount++
                } else {
                    val assetFile = File(tempAssetsDir, entryName)
                    assetFile.parentFile.mkdirs()
                    FileOutputStream(assetFile).use { fos ->
                        zipStream.copyTo(fos)
                    }
                }
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }

        log("&a[Loader] Decrypted and loaded V5 ($fileCount files)")

        Client.scheduleTask {
            JSLoader.loadVirtualModule("V5/loader")
        }
    }

    private fun decryptToBytes(encryptedBase64: String, ivBase64: String): ByteArray {
        val key = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
        val iv = IvParameterSpec(Base64.getDecoder().decode(ivBase64))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return cipher.doFinal(Base64.getDecoder().decode(encryptedBase64))
    }
}