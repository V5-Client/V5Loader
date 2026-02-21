package com.v5.render.objects

import com.v5.swift.util.render.Layers as SwiftLayers
import com.v5.swift.util.render.Pipelines as SwiftPipelines

object RenderLayers {
    @JvmField val LINE_LIST = SwiftLayers.LINE_LIST
    @JvmField val LINE_LIST_ESP = SwiftLayers.LINE_LIST_ESP
    @JvmField val TRIANGLE_STRIP = SwiftLayers.TRIANGLE_STRIP
    @JvmField val TRIANGLE_STRIP_ESP = SwiftLayers.TRIANGLE_STRIP_ESP
}

object RenderPipelines {
    @JvmField val LINE_LIST = SwiftPipelines.LINE_LIST
    @JvmField val LINE_LIST_ESP = SwiftPipelines.LINE_LIST_ESP
    @JvmField val TRIANGLE_STRIP = SwiftPipelines.TRIANGLE_STRIP
    @JvmField val TRIANGLE_STRIP_ESP = SwiftPipelines.TRIANGLE_STRIP_ESP
}