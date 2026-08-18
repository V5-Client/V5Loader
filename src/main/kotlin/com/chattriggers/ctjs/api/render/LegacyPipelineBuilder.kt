package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.BlendFactor
import com.mojang.blaze3d.platform.CompareOp
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

object LegacyPipelineBuilder {
    private val pipelineList = mutableMapOf<String, RenderPipeline>()
    private var cull: Boolean? = null
    private var depth: Boolean? = null
    private var blend: Boolean? = null
    private var drawMode = Renderer.DrawMode.QUADS
    private var vertexFormat = Renderer.VertexFormat.POSITION_COLOR
    private var snippet = Renderer.RenderSnippet.POSITION_COLOR_SNIPPET

    fun begin(
        drawMode: Renderer.DrawMode = Renderer.DrawMode.QUADS,
        vertexFormat: Renderer.VertexFormat = Renderer.VertexFormat.POSITION_COLOR,
        snippet: Renderer.RenderSnippet = Renderer.RenderSnippet.POSITION_COLOR_SNIPPET
    ) = apply {
        this.drawMode = drawMode
        this.vertexFormat = vertexFormat
        this.snippet = snippet
    }

    fun enableBlend() = apply {
        blend = true
    }

    fun disableBlend() = apply {
        blend = false
    }

    fun enableCull() = apply {
        cull = true
    }

    fun disableCull() = apply {
        cull = false
    }

    fun enableDepth() = apply {
        depth = true
    }

    fun disableDepth() = apply {
        depth = false
    }

    fun build(): RenderPipeline {
        val key = state()
        return pipelineList.getOrPut(key) {
            if (snippet.pipeline != null) {
                return@getOrPut snippet.pipeline!!
            }

            val basePipeline = RenderPipeline.builder(requireNotNull(snippet.mcSnippet))
                .withLocation("ctjs/custom/pipeline${hashCode()}")
                .withPrimitiveTopology(drawMode.toTopology())
            if (blend == true) {
                basePipeline.withColorTargetState(
                    ColorTargetState(
                        BlendFunction(
                            BlendFactor.SRC_ALPHA,
                            BlendFactor.ONE_MINUS_SRC_ALPHA,
                            BlendFactor.ONE,
                            BlendFactor.ZERO,
                        )
                    )
                )
            }

            cull?.let(basePipeline::withCull)

            if (depth == true) {
                basePipeline
                    .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            }
            else if (depth == false) {
                basePipeline
                    .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
            }

            basePipeline.build()
        }
    }

    fun layer(): RenderType {
        val pipeline = build()
        val setup = RenderSetup.builder(pipeline).createRenderSetup()
        return RenderType.create("ctjs/${pipeline.hashCode()}", setup)
    }

    fun state(): String {
        return "LegacyPipelineBuilder[" +
                "cull=$cull, " +
                "depth=$depth, " +
                "blend=$blend, " +
                "drawMode=${drawMode.name}, " +
                "vertexFormat=${vertexFormat.name}, " +
                "snippet=${snippet.name}" +
                "]"
    }

    private fun Renderer.DrawMode.toTopology(): PrimitiveTopology = when (this) {
        Renderer.DrawMode.LINES -> PrimitiveTopology.LINES
        Renderer.DrawMode.LINE_STRIP -> PrimitiveTopology.DEBUG_LINE_STRIP
        Renderer.DrawMode.TRIANGLES -> PrimitiveTopology.TRIANGLES
        Renderer.DrawMode.TRIANGLE_STRIP -> PrimitiveTopology.TRIANGLE_STRIP
        Renderer.DrawMode.TRIANGLE_FAN -> PrimitiveTopology.TRIANGLE_FAN
        Renderer.DrawMode.QUADS -> PrimitiveTopology.QUADS
    }
}
