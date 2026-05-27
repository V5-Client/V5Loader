package com.v5.launch

object V5Native {
    private const val XOR_KEY = 0x5A

    private val jniClass: Class<*>? by lazy {
        try {
            Class.forName("com.v5.loader.JNI")
        } catch (_: Throwable) {
            null
        }
    }

    private val consumeTokenMethod by lazy {
        val cls = jniClass ?: return@lazy null
        cls.methods.firstOrNull { m ->
            m.name == decodeName(intArrayOf(57, 53, 52, 41, 47, 55, 63, 14, 53, 49, 63, 52)) &&
                m.parameterCount == 0
        }
    }

    private fun decodeName(obfuscated: IntArray): String {
        val out = CharArray(obfuscated.size)
        for (i in obfuscated.indices) {
            out[i] = (obfuscated[i] xor XOR_KEY).toChar()
        }
        return String(out)
    }

    @JvmStatic
    fun consumeToken(): String? = consumeTokenMethod?.invoke(null) as? String
}
