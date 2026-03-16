package com.v5.swift

import com.v5.swift.cache.CachedWorld
import com.v5.event.PacketEvent
import com.v5.swift.integration.HypixelManager
import com.v5.swift.nativepath.NativePathfinderJNI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class Swift : ClientModInitializer {

  companion object {
    @JvmField
    val CHUNKS_PER_TICK = 8

    @JvmField
    val MAXIMUM_CACHED_CHUNKS = 4096

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
      HypixelManager.onDisconnect()
    }
  }

}
