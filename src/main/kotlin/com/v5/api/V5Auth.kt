package com.v5.api

object V5Auth {
    @Volatile
    var internalToken: String? = null

    @JvmStatic
    fun getJwtToken(): String? {
        return internalToken
    }
}