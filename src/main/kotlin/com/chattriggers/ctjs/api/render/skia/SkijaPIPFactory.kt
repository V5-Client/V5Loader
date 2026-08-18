package com.chattriggers.ctjs.api.render.skia

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
//? if <26.2 {
/*import net.minecraft.client.renderer.MultiBufferSource
*///?} else {
import net.minecraft.client.renderer.SubmitNodeCollector
//?}

//? if <26.2 {
/*internal fun createSkijaPIP(factoryInput: Any?, pre: Boolean): PictureInPictureRenderer<*> {
    val buffers = factoryInput!!.javaClass.getMethod("bufferSource").invoke(factoryInput) as MultiBufferSource.BufferSource
    return if (pre) SkijaPrePIPRenderer(buffers) else SkijaPIPRenderer(buffers)
}

private open class SkijaPIPRenderer(buffers: MultiBufferSource.BufferSource) :
    PictureInPictureRenderer<SkijaPIP.State>(buffers) {
    private val surface = SkijaSurface()

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = SkijaPIP.State::class.java
    override fun getTextureLabel() = "V5 Skija"
    override fun renderToTexture(state: SkijaPIP.State, poseStack: PoseStack) = SkijaPIP.render(surface, state)

    override fun close() {
        surface.close()
        super.close()
    }
}

private class SkijaPrePIPRenderer(buffers: MultiBufferSource.BufferSource) : SkijaPIPRenderer(buffers) {
    @Suppress("UNCHECKED_CAST")
    override fun getRenderStateClass() = SkijaPIP.PreState::class.java as Class<SkijaPIP.State>
}
*///?} else {
internal fun createSkijaPIP(factoryInput: Any?, pre: Boolean): PictureInPictureRenderer<*> =
    if (pre) SkijaPrePIPRenderer() else SkijaPIPRenderer()

private open class SkijaPIPRenderer : PictureInPictureRenderer<SkijaPIP.State>() {
    private val surface = SkijaSurface()

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = SkijaPIP.State::class.java
    override fun getTextureLabel() = "V5 Skija"
    override fun renderToTexture(state: SkijaPIP.State, poseStack: PoseStack, collector: SubmitNodeCollector) =
        SkijaPIP.render(surface, state)

    override fun close() {
        surface.close()
        super.close()
    }
}

private class SkijaPrePIPRenderer : SkijaPIPRenderer() {
    @Suppress("UNCHECKED_CAST")
    override fun getRenderStateClass() = SkijaPIP.PreState::class.java as Class<SkijaPIP.State>
}
//?}
