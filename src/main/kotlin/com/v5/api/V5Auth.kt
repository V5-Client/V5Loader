package com.v5.api

import com.v5.launch.SecureLoader

object V5Auth {
    @JvmStatic
    fun getJwtToken(): String? = SecureLoader.getJwtToken()

    @JvmStatic
    fun getFreshJwtToken(): String? = SecureLoader.getFreshJwtToken()

    @JvmStatic
    fun setJwtToken(token: String?) {
        SecureLoader.setJwtToken(token)
    }

    @JvmStatic
    fun shutDownHard(): Nothing = SecureLoader.killClientHard()
}
