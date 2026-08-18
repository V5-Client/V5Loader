package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.RenderPipelines as MinecraftRenderPipelines
import java.util.Optional

object RenderPipelines {
    @JvmField
    val LINE_LIST: RenderPipeline = MinecraftRenderPipelines.LINES

    @JvmField
    val LINE_LIST_ESP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(MinecraftRenderPipelines.LINES_SNIPPET)
            .withLocation("ctjs/pipeline/lines_esp")
            .withDepthStencilState(Optional.empty())
            .build()
    )

    @JvmField
    val TRIANGLE_STRIP: RenderPipeline = MinecraftRenderPipelines.DEBUG_FILLED_BOX

    @JvmField
    val TRIANGLE_STRIP_ESP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(MinecraftRenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("ctjs/pipeline/quads_esp")
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build()
    )
}

object RenderLayers {
    @JvmField
    val LINE_LIST: RenderType = RenderTypes.lines()

    @JvmField
    val LINE_LIST_ESP: RenderType = RenderType.create(
        "ctjs_lines_esp",
        RenderSetup.builder(RenderPipelines.LINE_LIST_ESP)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    )

    @JvmField
    val TRIANGLE_STRIP: RenderType = RenderTypes.debugFilledBox()

    @JvmField
    val TRIANGLE_STRIP_ESP: RenderType = RenderType.create(
        "ctjs_quads_esp",
        RenderSetup.builder(RenderPipelines.TRIANGLE_STRIP_ESP)
            .sortOnUpload()
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    )

    @JvmStatic
    fun ensureRegistered() {
        LINE_LIST_ESP
        TRIANGLE_STRIP_ESP
    }
}
