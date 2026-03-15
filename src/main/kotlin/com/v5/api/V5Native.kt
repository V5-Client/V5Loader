package com.v5.api

object V5Native {
    private val jniClass: Class<*>? by lazy {
        try {
            Class.forName("com.v5.loader.JNI")
        } catch (_: Throwable) {
            null
        }
    }

    private fun findMethod(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method? {
        val cls = jniClass ?: return null
        return try {
            cls.getDeclaredMethod(name, *parameterTypes)
        } catch (_: Throwable) {
            null
        }
    }

    private val consumeTokenMethod by lazy { findMethod("consumeToken") }
    private val getHwidMethod by lazy { findMethod("getHwid") }
    private val decryptAesGcmMethod by lazy {
        findMethod(
            "decryptAesGcm",
            ByteArray::class.java,
            ByteArray::class.java,
            ByteArray::class.java
        )
    }
    private val antiTamperMethod by lazy { findMethod("runNativeAntiTamperChecks") }

    @JvmStatic
    fun consumeToken(): String? = consumeTokenMethod?.invoke(null) as? String

    @JvmStatic
    fun getHwid(): String? = getHwidMethod?.invoke(null) as? String

    @JvmStatic
    fun decryptAesGcm(encryptedBytes: ByteArray, contentKey: ByteArray, fileIv: ByteArray): ByteArray? {
        return decryptAesGcmMethod?.invoke(null, encryptedBytes, contentKey, fileIv) as? ByteArray
    }

    @JvmStatic
    fun runAntiTamperChecks(): Boolean = (antiTamperMethod?.invoke(null) as? Boolean) ?: false
}
