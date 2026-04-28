package com.v5.swift.integration

import com.v5.swift.cache.CachedWorld
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

object WynncraftManager {

  private const val CACHE_KEY = "wynncraft"

  @Volatile
  private var sessionActive = false

  fun init() {
    ClientPlayConnectionEvents.JOIN.register { _, _, client ->
      val active = isWynncraftHost(client.currentServerEntry?.address)
      sessionActive = active
      CachedWorld.setUnlimitedChunkCache(active)

      if (active) {
        CachedWorld.setWorldKey(CACHE_KEY)
        CachedWorld.load(CACHE_KEY)
      }
    }
  }

  fun onDisconnect() {
    if (!sessionActive) return
    sessionActive = false
    CachedWorld.saveAndClear(CACHE_KEY)
    CachedWorld.setUnlimitedChunkCache(false)
    CachedWorld.setWorldKey(null)
  }

  private fun isWynncraftHost(address: String?): Boolean {
    if (address.isNullOrBlank()) return false
    val host = address.substringBefore(':').trim().trimEnd('.').lowercase()
    return host == "wynncraft.com" || host.endsWith(".wynncraft.com")
  }
}
