package com.chattriggers.ctjs.internal.listeners

import com.chattriggers.ctjs.MCBlockPos
import com.chattriggers.ctjs.api.render.Renderer
import com.chattriggers.ctjs.internal.engine.CTEvents
import com.chattriggers.ctjs.api.triggers.CancellableEvent
import com.chattriggers.ctjs.api.triggers.TriggerType
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos

object WorldListener {
    var matrixStack: PoseStack? = null
        private set
    var submitNodeCollector: SubmitNodeCollector? = null
        private set
    private var deltaTicks: Float = 1f

    fun setDeltaTicks(ticks: Float) {
        deltaTicks = ticks
    }

    fun beginWorldRender(stack: PoseStack, collector: SubmitNodeCollector) {
        matrixStack = stack
        submitNodeCollector = collector
        triggerRenderStart()
    }

    fun triggerBlockOutline(bp: MCBlockPos): Boolean {
        if (!JSLoader.hasTriggers(TriggerType.BLOCK_HIGHLIGHT)) return false

        val event = CancellableEvent()
        TriggerType.BLOCK_HIGHLIGHT.triggerAll(BlockPos(bp), event)
        return event.isCanceled()
    }

    fun triggerRenderStart() {
        val stack = matrixStack ?: return
        if (!JSLoader.hasTriggers(TriggerType.PRE_RENDER_WORLD)) return
        Renderer.withMatrix(stack, deltaTicks) {
            TriggerType.PRE_RENDER_WORLD.triggerAll(deltaTicks)
        }
    }

    fun triggerRenderLast() {
        val stack = matrixStack ?: return
        if (JSLoader.hasTriggers(TriggerType.POST_RENDER_WORLD)) Renderer.withMatrix(stack, deltaTicks) {
            TriggerType.POST_RENDER_WORLD.triggerAll(deltaTicks)
        }
        CTEvents.POST_RENDER_WORLD.invoker().render(stack, deltaTicks)
    }
}
