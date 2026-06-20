package com.chattriggers.ctjs.api.world.pathfinding

import com.chattriggers.ctjs.api.triggers.PacketEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class Swift : ClientModInitializer {

  companion object {
    const val CHUNKS_PER_TICK = 8

    const val MAXIMUM_CACHED_CHUNKS = 4096

    @JvmField
    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
      Thread(r, "Swift-Pathfinder-${System.currentTimeMillis()}").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
      }
    }
  }

  override fun onInitializeClient() {
    NativePathfinderJNI.initialize()
    HypixelManager.init()
    WynncraftManager.init()
    CachedWorld.setWorldKey(null)

    PacketEvent.RECEIVE.register { packet ->
      CachedWorld.onPacketReceive(packet)
    }

    ClientTickEvents.END_CLIENT_TICK.register { client ->
      if (client.world != null) {
        CachedWorld.processPendingChunks()
      }
    }

    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
      WynncraftManager.onDisconnect()
      HypixelManager.onDisconnect()
    }
  }
}
