package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat.Mode
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.RenderPipelines as MinecraftRenderPipelines

object RenderPipelines {
    @JvmField
    val LINE_LIST: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(MinecraftRenderPipelines.LINES_SNIPPET)
            .withLocation("pipeline/ctjs_lines")
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, Mode.LINES)
            .withCull(false)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            .build()
    )

    @JvmField
    val LINE_LIST_ESP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(MinecraftRenderPipelines.LINES_SNIPPET)
            .withLocation("pipeline/ctjs_lines_esp")
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, Mode.LINES)
            .withCull(false)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build()
    )

    @JvmField
    val TRIANGLE_STRIP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(MinecraftRenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/ctjs_debug_filled_box")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
            .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build()
    )

    @JvmField
    val TRIANGLE_STRIP_ESP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(MinecraftRenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/ctjs_debug_filled_box_esp")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
            .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build()
    )
}

object RenderLayers {
    @JvmField
    val LINE_LIST: RenderType = RenderType.create(
        "line-list",
        RenderSetup.builder(RenderPipelines.LINE_LIST)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )

    @JvmField
    val LINE_LIST_ESP: RenderType = RenderType.create(
        "line-list-esp",
        RenderSetup.builder(RenderPipelines.LINE_LIST_ESP)
            .createRenderSetup()
    )

    @JvmField
    val TRIANGLE_STRIP: RenderType = RenderType.create(
        "triangle_strip",
        RenderSetup.builder(RenderPipelines.TRIANGLE_STRIP)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .sortOnUpload()
            .createRenderSetup()
    )

    @JvmField
    val TRIANGLE_STRIP_ESP: RenderType = RenderType.create(
        "triangle_strip_esp",
        RenderSetup.builder(RenderPipelines.TRIANGLE_STRIP_ESP)
            .sortOnUpload()
            .createRenderSetup()
    )
}
