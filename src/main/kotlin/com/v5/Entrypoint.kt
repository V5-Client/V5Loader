package com.v5

import com.v5.qol.DiscordRPC
import com.v5.render.helper.FrustumHolder
import dev.quiteboring.swift.event.WorldRenderEvent
import net.fabricmc.api.ClientModInitializer

class Entrypoint : ClientModInitializer {

    override fun onInitializeClient() {
        DiscordRPC.stayOn()

        WorldRenderEvent.LAST.register { ctx ->
            FrustumHolder.currentFrustum = ctx.frustum
        }
    }

}