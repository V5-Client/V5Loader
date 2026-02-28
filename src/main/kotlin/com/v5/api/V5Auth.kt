package com.v5.api

object V5Auth {
    private const val TOKEN_PROPERTY_KEY = "v5.token"

    @Volatile
    private var internalToken: String? = null

    @Volatile
    private var didConsumeInitialPropertyToken = false

    @JvmStatic
    fun getJwtToken(): String? {
        val token = internalToken
        if (!token.isNullOrBlank()) return token

        if (!didConsumeInitialPropertyToken) {
            synchronized(this) {
                if (!didConsumeInitialPropertyToken) {
                    val propertyToken = System.getProperty(TOKEN_PROPERTY_KEY)
                    if (!propertyToken.isNullOrBlank()) {
                        internalToken = propertyToken
                    }
                    System.clearProperty(TOKEN_PROPERTY_KEY)
                    didConsumeInitialPropertyToken = true
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
