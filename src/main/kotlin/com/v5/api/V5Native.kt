package com.v5.api

object V5Native {
    private val jniClass: Class<*>? by lazy {
        try {
            Class.forName("com.v5.loader.JNI")
        } catch (_: Throwable) {
            null
        }
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val cls = jniClass ?: return null
        val method = cls.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size
        } ?: return null
        return method.invoke(null, *args)
    }

    @JvmStatic
    fun consumeToken(): String? = call("consumeToken") as? String

    @JvmStatic
    fun getHwid(): String? = call("getHwid") as? String

    @JvmStatic
    fun decryptAesGcm(encryptedBytes: ByteArray, contentKey: ByteArray, fileIv: ByteArray): ByteArray? {
        return call("decryptAesGcm", encryptedBytes, contentKey, fileIv) as? ByteArray
    }

    @JvmStatic
    fun runAntiTamperChecks(): Boolean = (call("runNativeAntiTamperChecks") as? Boolean) ?: false
}
