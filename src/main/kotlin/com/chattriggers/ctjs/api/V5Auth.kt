package com.chattriggers.ctjs.api

import com.chattriggers.ctjs.internal.launch.SecureLoader

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
    fun shutDownHard(): Void = SecureLoader.killClientHard()
}
