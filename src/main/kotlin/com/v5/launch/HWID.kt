package com.v5.launch

import com.v5.api.V5Native
import java.security.MessageDigest

object HWID {
    @JvmStatic
    fun generateHWID(): String {
        return V5Native.getHwid().orEmpty().ifBlank { fallbackHwid() }
    }

    private fun fallbackHwid(): String {
        val material = listOf(
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
            System.getProperty("os.version").orEmpty(),
            System.getProperty("user.name").orEmpty(),
            System.getProperty("java.version").orEmpty(),
            System.getProperty("java.vendor").orEmpty(),
        ).joinToString("|")

        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return "jvm-" + digest.joinToString("") { "%02x".format(it) }
    }
}
