package com.v5

import com.v5.render.helper.FrustumHolder
import com.v5.event.WorldRenderEvent
import net.fabricmc.api.ClientModInitializer

class Entrypoint : ClientModInitializer {

    override fun onInitializeClient() {
        WorldRenderEvent.LAST.register { ctx ->
            FrustumHolder.currentFrustum = ctx.frustum
        }
    }

}
