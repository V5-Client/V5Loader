package com.chattriggers.ctjs.internal.launch

internal object V5TokenSource {
    private val consumeTokenMethods by lazy {
        listOf("com.v5.loader.JNI", "com.v5.loader.internal.V5Loader").mapNotNull { className ->
            runCatching { Class.forName(className).getMethod("consumeToken") }.getOrNull()
        }
    }

    @JvmStatic
    fun consumeToken(): String? =
        consumeTokenMethods.firstNotNullOfOrNull { method ->
            runCatching { (method.invoke(null) as? String)?.takeIf { it.isNotBlank() } }.getOrNull()
        }
}
