package com.v5.loader.internal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val revokedRefreshErrors = setOf(
    "INVALID_REFRESH_TOKEN",
    "REFRESH_TOKEN_EXPIRED",
    "REFRESH_TOKEN_REUSED",
    "SESSION_REVOKED",
)

internal fun sessionFile(gameDir: Path): Path = gameDir.resolve(".v5/session.json")

internal fun readRefreshToken(sessionFile: Path): String? {
    if (!sessionFile.isRegularFile()) return null
    return runCatching {
        JsonParser.parseString(sessionFile.readText()).asJsonObject["refresh_token"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.ifBlank { null }
    }.getOrNull()
}

internal fun persistRefreshToken(sessionFile: Path, refreshToken: String): Boolean {
    if (refreshToken.isBlank()) return false
    return try {
        Files.createDirectories(sessionFile.parent)
        sessionFile.writeText(JsonObject().apply {
            addProperty("refresh_token", refreshToken)
            addProperty("updated_at", System.currentTimeMillis() / 1000)
        }.toString())
        if (!System.getProperty("os.name", "").lowercase().contains("win")) {
            Files.setPosixFilePermissions(
                sessionFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun refreshSession(sessionFile: Path, refreshToken: String? = readRefreshToken(sessionFile)): String? {
    if (refreshToken.isNullOrBlank()) return null
    val request = JsonObject().apply { addProperty("refresh_token", refreshToken) }.toString()
    val (status, body) = V5Http.httpsPost(V5Http.BACKEND_HOST, "/api/auth/refresh", request) ?: return null
    val response = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
    val error = response["error"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val accessToken = response["access_token"]?.takeIf { it.isJsonPrimitive }?.asString
        ?: response["token"]?.takeIf { it.isJsonPrimitive }?.asString
    val rotatedRefreshToken = response["refresh_token"]?.takeIf { it.isJsonPrimitive }?.asString

    if (status != 200 || accessToken.isNullOrBlank() || rotatedRefreshToken.isNullOrBlank()) {
        if (error in revokedRefreshErrors) runCatching { Files.deleteIfExists(sessionFile) }
        return null
    }

    persistRefreshToken(sessionFile, rotatedRefreshToken)
    return accessToken
}
