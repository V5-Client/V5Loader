package com.v5.swift

import com.v5.swift.cache.CachedWorld
import com.v5.swift.event.PacketEvent
import com.v5.swift.integration.HypixelManager
import com.v5.swift.util.setting.Settings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class Swift : ClientModInitializer {

  companion object {
    @JvmField
    val settings = Settings()

    @JvmField
    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
      Thread(r, "Swift-Pathfinder-${System.currentTimeMillis()}").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
      }
    }
  }

  override fun onInitializeClient() {
    HypixelManager.init()

    // THESE COMMANDS ARE COMMENTED OUT FOR A REASON. DO NOT UNCOMMENT ON PUSH
    //HeatmapCommand.dispatch()
    //PathCommand.dispatch()
    //
    //WorldRenderEvent.LAST.register { ctx ->
    //  PathCommand.onRender(ctx)
    //  HeatmapCommand.onRender(ctx)
    //}

    PacketEvent.RECEIVE.register { packet ->
      if (settings.worldCache) {
        CachedWorld.onPacketReceive(packet)
      }
    }

    ClientTickEvents.END_CLIENT_TICK.register { client ->
      if (client.world != null && settings.worldCache) {
        CachedWorld.processPendingChunks()
      }
    }

    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
      HypixelManager.onDisconnect()
    }
  }

}