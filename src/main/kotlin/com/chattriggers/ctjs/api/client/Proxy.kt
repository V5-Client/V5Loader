package com.chattriggers.ctjs.api.client

data class Proxy(
    var ip: String,
    var port: Int,
    var name: String,
    var username: String,
    var password: String,
    var isEnabled: Boolean,
)
