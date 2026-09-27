package com.chattriggers.ctjs.internal.utils

internal object Platform {
    val isAndroid = System.getenv("ANDROID_ROOT") != null ||
        System.getProperty("java.home", "").startsWith("/data/") ||
        runCatching { Class.forName("android.os.Build") }.isSuccess
}
