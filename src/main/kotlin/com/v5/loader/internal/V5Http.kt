package com.v5.loader.internal

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object V5Http {
    const val BACKEND_HOST = "backend.rdbt.top"
    private const val HTTP_TIMEOUT_SECONDS = 60L
    private const val USER_AGENT = "V5Loader/1.1"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun httpsGetBytes(host: String, path: String, jwt: String = ""): ByteArray? {
        val url = "https://$host$path"
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .header("User-Agent", USER_AGENT)
            .GET()

        if (jwt.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $jwt")
        }

        return try {
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                logHttpFailure("GET", url, response.statusCode(), response.body().size)
                return null
            }
            response.body()
        } catch (e: Exception) {
            logTransportFailure("GET", url, e)
            null
        }
    }

    fun httpsGet(
        host: String,
        path: String,
        jwt: String = "",
    ): String {
        val url = "https://$host$path"
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .header("User-Agent", USER_AGENT)
            .GET()

        if (jwt.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $jwt")
        }
        return try {
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logHttpFailure("GET", url, response.statusCode(), response.body().length)
                return ""
            }
            response.body()
        } catch (e: Exception) {
            logTransportFailure("GET", url, e)
            ""
        }
    }

    fun httpsPost(host: String, path: String, jsonBody: String): String {
        val url = "https://$host$path"
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))

        return try {
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logHttpFailure("POST", url, response.statusCode(), response.body().length)
                return ""
            }
            response.body()
        } catch (e: Exception) {
            logTransportFailure("POST", url, e)
            ""
        }
    }

    fun fetchLoaderHash(token: String, hash: String, minecraftVersion: String): String {
        return httpsGet(
            BACKEND_HOST,
            "/api/hash/loader?hash=$hash&minecraft_version=$minecraftVersion",
            token,
        )
    }

    private fun logTransportFailure(method: String, url: String, error: Exception) {
        System.err.println(
            "[V5] Curl $method failed url=$url curl_error=${error.message ?: error.javaClass.simpleName}",
        )
    }

    private fun logHttpFailure(method: String, url: String, httpCode: Int, responseBytes: Int) {
        System.err.println(
            "[V5] HTTP $method returned non-200 url=$url http_code=$httpCode response_bytes=$responseBytes",
        )
    }
}
