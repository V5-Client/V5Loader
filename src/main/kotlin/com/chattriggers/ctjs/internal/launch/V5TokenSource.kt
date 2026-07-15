package com.chattriggers.ctjs.internal.launch

internal object V5TokenSource {
    private val consumeTokenMethod by lazy {
        runCatching {
            Class.forName("com.v5.loader.internal.V5Loader").getMethod("consumeToken")
        }.getOrNull()
    }

    @JvmStatic
    fun consumeToken(): String? =
        (consumeTokenMethod?.invoke(null) as? String)?.takeIf { it.isNotBlank() }
}
