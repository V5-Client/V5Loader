package com.v5.render.objects

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode
import net.minecraft.client.gl.RenderPipelines as MinecraftRenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayer.MultiPhase
import net.minecraft.client.render.RenderPhase
import net.minecraft.client.render.VertexFormats

object RenderPipelines {
    @JvmField val LINE_LIST: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet?>(MinecraftRenderPipelines.RENDERTYPE_LINES_SNIPPET))
            .withLocation("pipeline/lines")
            .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL, DrawMode.LINES)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(true)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .build()
    )

    @JvmField val LINE_LIST_ESP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet?>(MinecraftRenderPipelines.RENDERTYPE_LINES_SNIPPET))
            .withLocation("pipeline/lines")
            .withShaderDefine("shad")
            .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL, DrawMode.LINES)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build()
    )

    @JvmField val TRIANGLE_STRIP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet?>(MinecraftRenderPipelines.POSITION_COLOR_SNIPPET))
            .withLocation("pipeline/debug_filled_box")
            .withCull(false)
            .withVertexFormat(VertexFormats.POSITION_COLOR, DrawMode.TRIANGLE_STRIP)
            .withDepthWrite(true)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build()
    )

    @JvmField val TRIANGLE_STRIP_ESP: RenderPipeline = MinecraftRenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet?>(MinecraftRenderPipelines.POSITION_COLOR_SNIPPET))
            .withLocation("pipeline/debug_filled_box")
            .withCull(false)
            .withVertexFormat(VertexFormats.POSITION_COLOR, DrawMode.TRIANGLE_STRIP)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build()
    )
}

object RenderLayers {
    @JvmField val LINE_LIST: RenderLayer = RenderLayer.of(
        "line-list",
        RenderLayer.DEFAULT_BUFFER_SIZE,
        RenderPipelines.LINE_LIST,
        RenderLayer.MultiPhaseParameters.builder()
            .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
            .build(false)
    )

    @JvmField val LINE_LIST_ESP: RenderLayer = RenderLayer.of(
        "line-list-esp",
        RenderLayer.DEFAULT_BUFFER_SIZE,
        RenderPipelines.LINE_LIST_ESP,
        RenderLayer.MultiPhaseParameters.builder().build(false)
    )

    @JvmField val TRIANGLE_STRIP: MultiPhase = RenderLayer.of(
        "triangle_strip",
        RenderLayer.DEFAULT_BUFFER_SIZE,
        false,
        true,
        RenderPipelines.TRIANGLE_STRIP,
        RenderLayer.MultiPhaseParameters.builder()
            .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
            .build(false)
    )

    @JvmField val TRIANGLE_STRIP_ESP: MultiPhase = RenderLayer.of(
        "triangle_strip_esp",
        RenderLayer.DEFAULT_BUFFER_SIZE,
        false,
        true,
        RenderPipelines.TRIANGLE_STRIP_ESP,
        RenderLayer.MultiPhaseParameters.builder().build(false)
    )
}
