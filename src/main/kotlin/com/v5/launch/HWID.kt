package com.v5.launch

import com.v5.api.V5Native

object HWID {
    @JvmStatic
    fun generateHWID(): String {
        return V5Native.getHwid().orEmpty().ifBlank { "ERROR" }
    }
}
