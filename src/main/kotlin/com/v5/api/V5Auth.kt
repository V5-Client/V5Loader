package com.v5.api

object V5Auth {
    @Volatile
    var internalToken: String? = null

    @JvmStatic
    fun getJwtToken(): String? {
        val token = internalToken
        if (!token.isNullOrBlank()) return token

        val propertyToken = System.getProperty("v5.token")
        if (!propertyToken.isNullOrBlank()) {
            internalToken = propertyToken
            System.clearProperty("v5.token")
            return propertyToken
        }

        return null
    }

    @JvmStatic
    fun setJwtToken(token: String?) {
        if (token.isNullOrBlank()) return
        internalToken = token
    }
}
