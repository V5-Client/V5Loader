package com.v5.proxy

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object ProxyInfo {

    private val gson = Gson()

    private val cachedProxies = mutableListOf<Proxy>()
    private var isLoaded = false

    fun getProxies(): MutableList<Proxy> {
        if (!isLoaded) {
            loadProxies()
        }
        return cachedProxies
    }

    private fun loadProxies() {
        val file = getProxyConfigFile()
        val text = file.readText()

        cachedProxies.clear()
        if (text.isNotBlank()) {
            val loaded: List<Proxy>? = gson.fromJson(text, object : TypeToken<List<Proxy>>() {}.type)
            if (loaded != null) {
                cachedProxies.addAll(loaded)
            }
        }
        isLoaded = true
    }

    private fun saveProxies() {
        getProxyConfigFile().writeText(gson.toJson(cachedProxies))
    }

    fun addProxy(proxy: Proxy) {
        if (!isLoaded) loadProxies()

        if (proxy.isEnabled) {
            cachedProxies.forEach { it.isEnabled = false }
        }

        cachedProxies.add(proxy)
        saveProxies()
    }

    fun removeProxy(proxy: Proxy) {
        if (!isLoaded) loadProxies()
        cachedProxies.remove(proxy)
        saveProxies()
    }

    fun updateProxy(original: Proxy, newProxy: Proxy) {
        if (!isLoaded) loadProxies()

        val index = cachedProxies.indexOf(original)
        if (index != -1) {
            if (newProxy.isEnabled && !original.isEnabled) {
                cachedProxies.forEach { it.isEnabled = false }
            }

            cachedProxies[index] = newProxy
            saveProxies()
        }
    }

    fun setProxyEnabled(proxy: Proxy, enabled: Boolean) {
        if (!isLoaded) loadProxies()

        val target = cachedProxies.find { it == proxy } ?: return

        if (enabled) {
            cachedProxies.forEach { it.isEnabled = false }
        }
        target.isEnabled = enabled
        saveProxies()
    }

    fun getEnabledProxies(): List<Proxy> {
        return getProxies().filter { it.isEnabled }
    }

    private fun getProxyConfigFile(): File {
        val config = File("config/ChatTriggers/modules/V5Config/proxies_DO_NOT_SHARE.json")
        config.parentFile?.mkdirs()

        if (!config.exists()) {
            config.createNewFile()
            config.writeText("[]")
        }

        return config
    }
}