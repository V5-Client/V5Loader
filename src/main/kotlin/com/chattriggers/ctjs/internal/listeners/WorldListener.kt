package com.chattriggers.ctjs.internal.listeners

import com.chattriggers.ctjs.MCBlockPos
import com.chattriggers.ctjs.api.render.Renderer
import com.chattriggers.ctjs.api.triggers.CancellableEvent
import com.chattriggers.ctjs.api.triggers.TriggerType
import com.chattriggers.ctjs.internal.engine.JSLoader
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.BlockPos

object WorldListener {
    var matrixStack: MatrixStack? = null
    private var deltaTicks: Float = 1f

    fun triggerBlockOutline(bp: MCBlockPos): Boolean {
        if (!JSLoader.hasTriggers(TriggerType.BLOCK_HIGHLIGHT)) return false

        val event = CancellableEvent()
        TriggerType.BLOCK_HIGHLIGHT.triggerAll(BlockPos(bp), event)
        return event.isCanceled()
    }

    fun triggerRenderStart(ticks: Float) {
        deltaTicks = ticks
        if (matrixStack == null || !JSLoader.hasTriggers(TriggerType.PRE_RENDER_WORLD)) return
        Renderer.withMatrix(matrixStack, ticks) {
            TriggerType.PRE_RENDER_WORLD.triggerAll(ticks)
        }
    }

    fun triggerRenderLast() {
        if (matrixStack == null || !JSLoader.hasTriggers(TriggerType.POST_RENDER_WORLD)) return
        Renderer.withMatrix(matrixStack, deltaTicks) {
            TriggerType.POST_RENDER_WORLD.triggerAll(deltaTicks)
        }
    }
}
