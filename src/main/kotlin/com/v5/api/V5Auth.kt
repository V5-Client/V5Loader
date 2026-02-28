package com.v5.api

object V5Auth {
    @Volatile
    var internalToken: String? = null

    @Volatile
    private var didConsumeInitialPropertyToken = false

    @JvmStatic
    fun getJwtToken(): String? {
        if (!didConsumeInitialPropertyToken) {
            synchronized(this) {
                if (!didConsumeInitialPropertyToken) {
                    val propertyToken = System.getProperty(v5.token)
                    internalToken = propertyToken?.takeIf { it.isNotBlank() }
                    System.clearProperty(v5.token)
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
