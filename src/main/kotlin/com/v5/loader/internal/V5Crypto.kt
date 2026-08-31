package com.v5.loader.internal

import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import kotlin.io.path.inputStream

internal object V5Crypto {
    private val secureRandom = SecureRandom()

    fun calculateSha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun calculateFileSha256(path: String): String {
        repeat(10) { attempt ->
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                Path.of(path).inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                return HexFormat.of().formatHex(digest.digest())
            } catch (_: Exception) {
                if (attempt < 9) Thread.sleep(100)
            }
        }
        return ""
    }

    fun generateUrlSafeNonce(byteLen: Int = 16): String {
        if (byteLen <= 0) return ""
        val nonceBytes = ByteArray(byteLen)
        secureRandom.nextBytes(nonceBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
    }
}
