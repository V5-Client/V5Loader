package dev.quiteboring.swift.integration

import dev.quiteboring.swift.cache.CachedWorld
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket

object HypixelManager {

  private var currentLobby: String? = null

  fun init() {
    val api = HypixelModAPI.getInstance()

    api.subscribeToEventPacket(ClientboundLocationPacket::class.java)

    api.createHandler(ClientboundLocationPacket::class.java) { packet ->
      val serverName = packet.getServerName()
      val serverType = packet.getServerType().map { it.name }.orElse("")
      val lobbyName = packet.getLobbyName().orElse("")
      val mode = packet.getMode().orElse("")
      val map = packet.getMap().orElse("")

      val rawId = when {
        serverType == "SKYBLOCK" -> {
          if (mode.isNotEmpty()) "skyblock_$mode" else "skyblock_generic"
        }
        map.isNotEmpty() -> "map_$map"
        lobbyName.isNotEmpty() -> "lobby_$lobbyName"
        serverType.isNotEmpty() -> "type_$serverType"
        else -> "server_$serverName"
      }

      val newLobby = rawId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

      if (currentLobby != newLobby) {
        if (currentLobby != null) {
          CachedWorld.saveAndClear(currentLobby!!)
        } else {
          CachedWorld.clear()
        }

        CachedWorld.load(newLobby)
        currentLobby = newLobby
      }
    }
  }

  fun onDisconnect() {
    if (currentLobby != null) {
      CachedWorld.saveAndClear(currentLobby!!)
      currentLobby = null
    } else {
      CachedWorld.clear()
    }
  }
}
