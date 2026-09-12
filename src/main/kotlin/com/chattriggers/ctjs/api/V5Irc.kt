package com.chattriggers.ctjs.api

import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.message.Chat
import com.chattriggers.ctjs.internal.launch.SecureLoader
import com.v5.loader.internal.V5Http
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object V5Irc {
    @JvmStatic @Volatile var enabled = true
    @JvmStatic @Volatile var autoMeow = false
    @JvmStatic @Volatile var randomChoiceMeow = true

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "V5 IRC").apply { isDaemon = true }
    }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val connectionKey = UUID.randomUUID().toString().replace("-", "")
    private val meows = listOf("meow!", "mrrp!", "mreow!", "mroew!", "mew!", "mrow!", "nya!", "prrrt!", "mraow!", "mrrow!")
    private val muteDateFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private var active: SocketListener? = null
    private var retry: ScheduledFuture<*>? = null
    private var attempts = 0
    private var stopped = false

    internal fun stop() {
        worker.execute {
            stopped = true
            retry?.cancel(false)
            active?.socket?.abort()
            active = null
        }
    }

    @JvmStatic
    fun reconnect() {
        worker.execute {
            if (stopped) return@execute
            attempts = 0
            retry?.cancel(false)
            connect()
        }
    }

    @JvmStatic
    fun send(content: String) {
        worker.execute {
            val listener = active ?: return@execute
            val socket = listener.socket ?: return@execute
            if (!listener.authenticated) {
                chat("&cAuthenticate V5 with Discord before sending IRC messages.")
                return@execute
            }
            try {
                socket.sendText(content, true).orTimeout(10, TimeUnit.SECONDS).join()
            } catch (e: Exception) {
                chat("Failed to send message")
                listener.disconnect(error = e)
            }
        }
    }

    private fun connect() {
        if (stopped) return
        active?.socket?.abort()
        val token = SecureLoader.getFreshJwtToken()
        val listener = SocketListener(!token.isNullOrBlank())
        active = listener
        try {
            val builder = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", if (token.isNullOrBlank()) "Guest" else "Bearer $token")
                .header("X-Connection-Key", connectionKey)
            builder
                .buildAsync(URI("wss://${V5Http.BACKEND_HOST}/api/chat"), listener)
                .whenComplete { _, error ->
                    if (error != null) worker.execute { listener.disconnect(error = error) }
                }
        } catch (e: Exception) {
            listener.disconnect(error = e)
        }
    }

    private class SocketListener(val authenticated: Boolean) : WebSocket.Listener {
        var socket: WebSocket? = null
        private var connectedAt = 0L
        private val message = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            worker.execute {
                if (active !== this || stopped) {
                    webSocket.abort()
                } else {
                    socket = webSocket
                    connectedAt = System.nanoTime()
                    webSocket.request(1)
                }
            }
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val fragment = data.toString()
            worker.execute {
                if (active !== this || stopped) return@execute
                if (message.length + fragment.length > 1_048_576) {
                    disconnect(error = IllegalArgumentException("IRC message exceeds 1 MiB"))
                    return@execute
                }
                message.append(fragment)
                if (last) {
                    handleMessage(message.toString())
                    message.setLength(0)
                }
                webSocket.request(1)
            }
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            worker.execute { disconnect(statusCode) }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            worker.execute { disconnect(error = error) }
        }

        fun disconnect(code: Int? = null, error: Throwable? = null) {
            if (active !== this) return
            active = null
            socket?.abort()
            if (stopped || code == WebSocket.NORMAL_CLOSURE) return
            if (connectedAt != 0L && System.nanoTime() - connectedAt >= TimeUnit.SECONDS.toNanos(10)) attempts = 0
            if (error != null) {
                chat("Connection error: ${error.cause?.message ?: error.message}")
                error.printStackTrace()
            }
            val delay = if (attempts == 0) 0L else (1L shl attempts.coerceAtMost(6)).coerceAtMost(60)
            attempts = (attempts + 1).coerceAtMost(7)
            retry = worker.schedule({ connect() }, delay, TimeUnit.SECONDS)
        }
    }

    private fun handleMessage(raw: String) {
        try {
            val data = Json.parseToJsonElement(raw) as? JsonObject ?: return
            fun value(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            when (value("type")) {
                "remote" -> if (value("action") == "crash_game") {
                    stopped = true
                    retry?.cancel(false)
                    active?.socket?.abort()
                    active = null
                    SecureLoader.killClientHard()
                }
                "message" -> if (enabled) {
                    chat("&9${value("user").ifEmpty { "Unknown" }}&r: ${value("msg")}")
                    if (autoMeow && value("msg").trim().equals("meow", ignoreCase = true)) {
                        send(if (randomChoiceMeow) meows.random() else "meow!")
                    }
                }
                "error" -> chat("Error: ${value("code").ifEmpty { "Unknown" }}")
                "system" -> chat(when (value("code")) {
                    "PREFIX_UPDATED" -> "Your prefix has been changed"
                    "MUTED" -> {
                        val expiry = value("mute_expires_at").toDoubleOrNull()?.takeIf { it.isFinite() }
                        val date = expiry?.let { runCatching { muteDateFormat.format(Instant.ofEpochMilli((it * 1000).toLong())) }.getOrNull() }
                        if (date == null) "You have been muted" else "You have been muted until $date"
                    }
                    else -> "System: ${value("code")}"
                })
                "announcement" -> if (enabled && value("msg").isNotEmpty()) {
                    Client.getMinecraft().execute {
                        Chat.sendGradientMsg("V5 Announcement »", 0xf4a261, 0xe76f51, value("msg"))
                    }
                }
            }
        } catch (e: Exception) {
            chat("An error occurred parsing message")
            e.printStackTrace()
        }
    }

    private fun chat(message: String) {
        Client.getMinecraft().execute { Chat.sendGradientMsg("IRC »", 0x05b9f9, 0x0539f9, message) }
    }
}
