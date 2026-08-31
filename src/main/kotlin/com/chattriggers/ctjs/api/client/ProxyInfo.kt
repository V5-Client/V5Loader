package com.chattriggers.ctjs.api.client

import com.google.gson.Gson
import java.io.File

object ProxyInfo {

    private val gson = Gson()

    private val cachedProxies = mutableListOf<Proxy>()
    private var isLoaded = false

    @JvmStatic
    fun getProxies(): List<Proxy> {
        ensureLoaded()
        return cachedProxies.map { it.copy() }
    }

    private fun loadProxies() {
        val file = getProxyConfigFile()
        val text = file.readText()

        cachedProxies.clear()
        if (text.isNotBlank()) {
            val parsed = parseProxies(text)
            val normalized = normalizeProxies(parsed)
            cachedProxies.addAll(normalized)

            if (normalized != parsed) {
                saveProxies()
            }
        }
        isLoaded = true
    }

    private fun saveProxies() {
        val normalized = normalizeProxies(cachedProxies)
        cachedProxies.clear()
        cachedProxies.addAll(normalized)
        getProxyConfigFile().writeText(gson.toJson(cachedProxies))
    }

    @JvmStatic
    fun addProxy(proxy: Proxy) {
        ensureLoaded()

        if (proxy.isEnabled) {
            cachedProxies.forEach { it.isEnabled = false }
        }

        cachedProxies.add(proxy.copy())
        saveProxies()
    }

    @JvmStatic
    fun removeProxy(proxy: Proxy) {
        ensureLoaded()
        val index = cachedProxies.indexOfMatching(proxy)
        if (index != -1) {
            cachedProxies.removeAt(index)
            saveProxies()
        }
    }

    @JvmStatic
    fun updateProxy(original: Proxy, newProxy: Proxy) {
        ensureLoaded()

        val index = cachedProxies.indexOfMatching(original)
        if (index != -1) {
            if (newProxy.isEnabled && !cachedProxies[index].isEnabled) {
                cachedProxies.forEach { it.isEnabled = false }
            }

            cachedProxies[index] = newProxy.copy()
            saveProxies()
        }
    }

    @JvmStatic
    fun setProxyEnabled(proxy: Proxy, enabled: Boolean) {
        ensureLoaded()

        val targetIndex = cachedProxies.indexOfMatching(proxy)
        if (targetIndex == -1) return

        if (enabled) {
            cachedProxies.forEach { it.isEnabled = false }
        }
        cachedProxies[targetIndex].isEnabled = enabled
        saveProxies()
    }

    @JvmStatic
    fun getEnabledProxies(): List<Proxy> {
        return getProxies().filter { it.isEnabled }
    }

    private fun getProxyConfigFile(): File {
        val config = File("config/ChatTriggers/modules/V5Config/proxies_DO_NOT_SHARE.json")
        config.parentFile?.mkdirs()

        if (!config.exists()) {
            config.writeText("[]")
        }

        return config
    }

    private fun parseProxies(text: String) = runCatching {
        gson.fromJson(text, Array<StoredProxy>::class.java).orEmpty().map {
            Proxy(
                it.ip.orEmpty(),
                it.port?.takeIf { port -> port in 1..65535 } ?: 1080,
                it.name ?: "Proxy",
                it.username.orEmpty(),
                it.password.orEmpty(),
                it.isEnabled ?: false,
            )
        }
    }.getOrDefault(emptyList())

    private fun normalizeProxies(input: List<Proxy>): List<Proxy> {
        val deduped = input.groupBy { it.copy(isEnabled = false) }.map { (proxy, copies) ->
            proxy.copy(isEnabled = copies.any { it.isEnabled })
        }
        deduped.dropWhile { !it.isEnabled }.drop(1).forEach { it.isEnabled = false }
        return deduped
    }

    private fun ensureLoaded() {
        if (!isLoaded) loadProxies()
    }

    private fun List<Proxy>.indexOfMatching(proxy: Proxy) =
        indexOfFirst { it.copy(isEnabled = false) == proxy.copy(isEnabled = false) }

    private class StoredProxy {
        var ip: String? = null
        var port: Int? = null
        var name: String? = null
        var username: String? = null
        var password: String? = null
        var isEnabled: Boolean? = null
    }
}
