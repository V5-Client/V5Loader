package com.chattriggers.ctjs.internal.utils

internal object Platform {
    val isAndroid = runCatching { Class.forName("android.os.Build") }.isSuccess
}
