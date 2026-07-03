package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.platform.DestFactor
import com.mojang.blaze3d.platform.SourceFactor
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderSetup

object LegacyPipelineBuilder {
    private val layerList = mutableMapOf<String, RenderType>()
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

    fun enabledBlend() = apply {
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
        if (pipelineList.containsKey(state())) return pipelineList[state()]!!

        val basePipeline = RenderPipeline.builder(snippet.mcSnippet)
            .withLocation("ctjs/custom/pipeline${hashCode()}")
            .withVertexFormat(vertexFormat.toMC(), drawMode.toUC().mcMode)
        if (blend == true) basePipeline.withColorTargetState(ColorTargetState(BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ZERO)))

        if (cull != null) basePipeline.withCull(cull!!)

        if (depth == true) {
            basePipeline
                .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        }
        else if (depth == false) {
            basePipeline
                .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
        }

        val pipeline = basePipeline.build()
        pipelineList[state()] = pipeline

        return pipeline
    }

    fun layer(): RenderType {
        if (layerList.containsKey(state())) return layerList[state()]!!

        // FIXME: icba to do this
//        val layer = RenderLayer.of(
//            "ctjs/custom/layer${hashCode()}",
//            1536,
//            build(),
//            RenderLayer.MultiPhaseParameters
//                .builder()
//                .build(false)
//        )
//        layerList[state()] = layer

        throw Error("LegacyPipelineBuilder#layer currently not supported")
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
}
