package com.v5.proxy

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object ProxyInfo {

    private val gson = Gson()

    private val cachedProxies = mutableListOf<Proxy>()
    private var isLoaded = false

    fun getProxies(): List<Proxy> {
        if (!isLoaded) {
            loadProxies()
        }
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
        getProxyConfigFile().writeText(serializeProxies(cachedProxies))
    }

    fun addProxy(proxy: Proxy) {
        if (!isLoaded) loadProxies()

        if (proxy.isEnabled) {
            cachedProxies.forEach { it.isEnabled = false }
        }

        cachedProxies.add(proxy.copy())
        saveProxies()
    }

    fun removeProxy(proxy: Proxy) {
        if (!isLoaded) loadProxies()
        val index = cachedProxies.indexOfFirst { it.sameIdentity(proxy) }
        if (index != -1) {
            cachedProxies.removeAt(index)
            saveProxies()
        }
    }

    fun updateProxy(original: Proxy, newProxy: Proxy) {
        if (!isLoaded) loadProxies()

        val index = cachedProxies.indexOfFirst { it.sameIdentity(original) }
        if (index != -1) {
            if (newProxy.isEnabled && !cachedProxies[index].isEnabled) {
                cachedProxies.forEach { it.isEnabled = false }
            }

            cachedProxies[index] = newProxy.copy()
            saveProxies()
        }
    }

    fun setProxyEnabled(proxy: Proxy, enabled: Boolean) {
        if (!isLoaded) loadProxies()

        val targetIndex = cachedProxies.indexOfFirst { it.sameIdentity(proxy) }
        if (targetIndex == -1) return

        if (enabled) {
            cachedProxies.forEach { it.isEnabled = false }
        }
        cachedProxies[targetIndex].isEnabled = enabled
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

    private fun parseProxies(text: String): List<Proxy> {
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return emptyList()
        if (!element.isJsonArray) return emptyList()

        return element.asJsonArray.mapNotNull { entry ->
            val obj = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            jsonToProxy(obj)
        }
    }

    private fun normalizeProxies(input: List<Proxy>): List<Proxy> {
        if (input.isEmpty()) return emptyList()

        val deduped = LinkedHashMap<ProxyIdentity, Proxy>()
        input.forEach { proxy ->
            val key = proxy.identity()
            val existing = deduped[key]
            if (existing == null) {
                deduped[key] = proxy.copy()
            } else if (proxy.isEnabled) {
                existing.isEnabled = true
            }
        }

        var enabledSeen = false
        deduped.values.forEach { proxy ->
            if (proxy.isEnabled && !enabledSeen) {
                enabledSeen = true
            } else {
                proxy.isEnabled = false
            }
        }

        return deduped.values.toList()
    }

    private fun serializeProxies(proxies: List<Proxy>): String {
        val json = JsonArray()
        proxies.forEach { proxy ->
            json.add(proxyToJson(proxy))
        }
        return gson.toJson(json)
    }

    private fun jsonToProxy(obj: JsonObject): Proxy {
        return Proxy(
            ip = obj.getString("ip", ""),
            port = obj.getInt("port", 1080),
            name = obj.getString("name", "Proxy"),
            username = obj.getString("username", ""),
            password = obj.getString("password", ""),
            isEnabled = obj.getBoolean("isEnabled", false)
        )
    }

    private fun proxyToJson(proxy: Proxy): JsonObject {
        return JsonObject().apply {
            addProperty("ip", proxy.ip)
            addProperty("port", proxy.port)
            addProperty("name", proxy.name)
            addProperty("username", proxy.username)
            addProperty("password", proxy.password)
            addProperty("isEnabled", proxy.isEnabled)
        }
    }

    private fun JsonObject.getString(key: String, fallback: String): String {
        val value = this.get(key)
        return if (value != null && !value.isJsonNull) value.asString else fallback
    }

    private fun JsonObject.getInt(key: String, fallback: Int): Int {
        val value = this.get(key)
        return if (value != null && !value.isJsonNull) runCatching { value.asInt }.getOrDefault(fallback) else fallback
    }

    private fun JsonObject.getBoolean(key: String, fallback: Boolean): Boolean {
        val value = this.get(key)
        return if (value != null && !value.isJsonNull) runCatching { value.asBoolean }.getOrDefault(fallback) else fallback
    }

    private fun Proxy.sameIdentity(other: Proxy): Boolean {
        return this.identity() == other.identity()
    }

    private fun Proxy.identity(): ProxyIdentity {
        return ProxyIdentity(
            ip = ip,
            port = port,
            name = name,
            username = username,
            password = password
        )
    }

    private data class ProxyIdentity(
        val ip: String,
        val port: Int,
        val name: String,
        val username: String,
        val password: String
    )
}
