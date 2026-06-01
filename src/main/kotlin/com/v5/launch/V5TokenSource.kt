package com.v5.launch

import java.lang.reflect.Method
import java.lang.reflect.Modifier

object V5TokenSource {
    private val tokenMethod by lazy {
        findTokenMethod("com.v5.loader.internal.V5Loader")
            ?: findTokenMethod("com.v5.loader.JNI")
    }

    @JvmStatic
    fun consumeToken(): String? = tokenMethod?.invoke() as? String

    private fun findTokenMethod(className: String): TokenMethod? {
        return runCatching {
            val cls = Class.forName(className)
            val method = cls.methods.firstOrNull { it.name == "consumeToken" && it.parameterCount == 0 }
                ?: return null
            TokenMethod(method, resolveReceiver(cls, method))
        }.getOrNull()
    }

    private fun resolveReceiver(cls: Class<*>, method: Method): Any? {
        if (Modifier.isStatic(method.modifiers)) return null
        return cls.getField("INSTANCE").get(null)
    }

    private data class TokenMethod(
        val method: Method,
        val receiver: Any?,
    ) {
        fun invoke(): Any? = method.invoke(receiver)
    }
}
