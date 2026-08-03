package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat.Mode
import net.minecraft.client.renderer.RenderPipelines as MinecraftRenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import java.util.Optional

/** The only custom world pipelines are the two states vanilla does not expose: no-depth ESP. */
object RenderPipelines {
    @JvmField val LINE_LIST = MinecraftRenderPipelines.LINES_TRANSLUCENT
    @JvmField val TRIANGLE_STRIP = MinecraftRenderPipelines.DEBUG_FILLED_BOX

    @JvmField
    val LINE_LIST_ESP: RenderPipeline = RenderPipeline.builder(MinecraftRenderPipelines.LINES_SNIPPET)
        .withLocation("pipeline/ctjs_lines_esp")
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, Mode.LINES)
        .withCull(false)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(Optional.empty())
        .build()

    @JvmField
    val TRIANGLE_STRIP_ESP: RenderPipeline = RenderPipeline.builder(MinecraftRenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation("pipeline/ctjs_filled_box_esp")
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
        .withCull(false)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(Optional.empty())
        .build()
}

object RenderLayers {
    @JvmField
    val LINE_LIST: RenderType = RenderType.create(
        "line-list",
        RenderSetup.builder(RenderPipelines.LINE_LIST)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup(),
    )

    @JvmField
    val LINE_LIST_ESP: RenderType = RenderType.create(
        "line-list-esp",
        RenderSetup.builder(RenderPipelines.LINE_LIST_ESP).createRenderSetup(),
    )

    @JvmField
    val TRIANGLE_STRIP: RenderType = RenderType.create(
        "filled-box",
        RenderSetup.builder(RenderPipelines.TRIANGLE_STRIP)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .sortOnUpload()
            .createRenderSetup(),
    )

    @JvmField
    val TRIANGLE_STRIP_ESP: RenderType = RenderType.create(
        "filled-box-esp",
        RenderSetup.builder(RenderPipelines.TRIANGLE_STRIP_ESP).sortOnUpload().createRenderSetup(),
    )
}
