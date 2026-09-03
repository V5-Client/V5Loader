package com.chattriggers.ctjs.api

import com.chattriggers.ctjs.internal.launch.SecureLoader
import java.util.concurrent.CompletableFuture

object V5Auth {
    @JvmStatic
    fun authenticate(): CompletableFuture<String?> = CompletableFuture.supplyAsync(SecureLoader::authenticate)

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
