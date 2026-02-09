package com.v5.proxy

data class Proxy(
    var ip: String,
    var port: Int,
    var name: String,
    var username: String,
    var password: String,
    var isEnabled: Boolean,
)