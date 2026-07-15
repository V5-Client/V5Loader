package com.chattriggers.ctjs.internal.launch

import java.lang.reflect.Modifier

internal object V5TokenSource {
    private val initMethod by lazy {
        runCatching {
            Class.forName("com.v5.loader.internal.V5Loader").getMethod("init")
                .takeIf { Modifier.isStatic(it.modifiers) }
        }.getOrNull()
    }

    private val consumeTokenMethod by lazy {
        runCatching {
            Class.forName("com.v5.loader.internal.V5Loader").getMethod("consumeToken")
        }.getOrNull()
    }

    @JvmStatic
    fun consumeToken(): String? {
        initMethod?.invoke(null)
        return (consumeTokenMethod?.invoke(null) as? String)?.takeIf { it.isNotBlank() }
    }
}
