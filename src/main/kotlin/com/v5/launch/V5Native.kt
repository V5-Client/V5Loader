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

    private val nativeMethods by lazy {
        val cls = jniClass ?: return@lazy null
        val methods = cls.methods
        NativeMethodHandles(
            consumeToken = methods.firstOrNull { m ->
                m.name == decodeName(intArrayOf(57, 53, 52, 41, 47, 55, 63, 14, 53, 49, 63, 52)) &&
                    m.parameterCount == 0
            },
            getHwid = methods.firstOrNull { m ->
                m.name == decodeName(intArrayOf(61, 63, 46, 18, 45, 51, 62)) &&
                    m.parameterCount == 0
            },
            decryptAesGcm = methods.firstOrNull { m ->
                m.name == decodeName(intArrayOf(62, 63, 57, 40, 35, 42, 46, 27, 63, 41, 29, 57, 55)) &&
                    m.parameterCount == 3
            },
            runNativeAntiTamperChecks = methods.firstOrNull { m ->
                m.name == decodeName(
                    intArrayOf(
                        40, 47, 52, 20, 59, 46, 51, 44, 63, 27, 52, 46, 51, 14, 59, 55, 42, 63, 40, 25, 50, 63, 57, 49, 41
                    )
                ) && m.parameterCount == 0
            }
        )
    }

    private data class NativeMethodHandles(
        val consumeToken: java.lang.reflect.Method?,
        val getHwid: java.lang.reflect.Method?,
        val decryptAesGcm: java.lang.reflect.Method?,
        val runNativeAntiTamperChecks: java.lang.reflect.Method?
    )

    private fun decodeName(obfuscated: IntArray): String {
        val out = CharArray(obfuscated.size)
        for (i in obfuscated.indices) {
            out[i] = (obfuscated[i] xor XOR_KEY).toChar()
        }
        return String(out)
    }

    @JvmStatic
    fun consumeToken(): String? = nativeMethods?.consumeToken?.invoke(null) as? String

    @JvmStatic
    fun getHwid(): String? = nativeMethods?.getHwid?.invoke(null) as? String

    @JvmStatic
    fun decryptAesGcm(encryptedBytes: ByteArray, contentKey: ByteArray, fileIv: ByteArray): ByteArray? {
        return nativeMethods?.decryptAesGcm?.invoke(null, encryptedBytes, contentKey, fileIv) as? ByteArray
    }

    @JvmStatic
    fun runAntiTamperChecks(): Boolean {
        return (nativeMethods?.runNativeAntiTamperChecks?.invoke(null) as? Boolean) ?: false
    }
}
