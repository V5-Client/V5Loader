package com.v5.api

object V5Auth {
    @Volatile
    private var internalToken: String? = null

    @Volatile
    private var didConsumeInitialNativeToken = false

    @JvmStatic
    fun getJwtToken(): String? {
        val token = internalToken
        if (!token.isNullOrBlank()) return token

        if (!didConsumeInitialNativeToken) {
            synchronized(this) {
                if (!didConsumeInitialNativeToken) {
                    val nativeToken = V5Native.consumeToken()
                    if (!nativeToken.isNullOrBlank()) {
                        internalToken = nativeToken
                    }
                    didConsumeInitialNativeToken = true
                }
            }
        }

        return internalToken
    }

    @JvmStatic
    fun setJwtToken(token: String?) {
        if (token.isNullOrBlank()) return
        internalToken = token
    }
}
