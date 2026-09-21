package com.chattriggers.ctjs.api.render.skia

//? if >=26.3 {
/*import com.chattriggers.ctjs.internal.mixins.PictureInPictureRendererAccessor
*///?}
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.Context
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
//? if <26.2 {
/*import net.minecraft.client.renderer.MultiBufferSource
*///?} else {
import net.minecraft.client.renderer.SubmitNodeCollector
//?}

//? if <26.2 {
/*internal fun createSkijaPIP(context: Context, pre: Boolean): PictureInPictureRenderer<*> {
    val buffers = context.bufferSource()
    return if (pre) SkijaPrePIPRenderer(buffers) else SkijaPIPRenderer(buffers)
}
internal fun createInventorySkijaPIP(context: Context): PictureInPictureRenderer<*> = CachedSkijaPIPRenderer(context.bufferSource(), SkijaPIP.InventoryState::class.java)
internal fun createStatsSkijaPIP(context: Context): PictureInPictureRenderer<*> = CachedSkijaPIPRenderer(context.bufferSource(), SkijaPIP.StatsState::class.java)
internal fun createPanelsSkijaPIP(context: Context): PictureInPictureRenderer<*> = CachedSkijaPIPRenderer(context.bufferSource(), SkijaPIP.PanelsState::class.java)

private open class SkijaPIPRenderer(buffers: MultiBufferSource.BufferSource) :
    PictureInPictureRenderer<SkijaPIP.State>(buffers) {
    private val surface = SkijaSurface()

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = SkijaPIP.State::class.java
    override fun getTextureLabel() = "V5 Skija"
    override fun renderToTexture(state: SkijaPIP.State, poseStack: PoseStack) { SkijaPIP.render(surface, state) }

    override fun close() {
        surface.close()
        super.close()
    }
}

private class SkijaPrePIPRenderer(buffers: MultiBufferSource.BufferSource) : SkijaPIPRenderer(buffers) {
    @Suppress("UNCHECKED_CAST")
    override fun getRenderStateClass() = SkijaPIP.PreState::class.java as Class<SkijaPIP.State>
}

private class CachedSkijaPIPRenderer<T : SkijaPIP.CachedState>(buffers: MultiBufferSource.BufferSource, private val stateClass: Class<T>) : PictureInPictureRenderer<T>(buffers) {
    private val surface = SkijaSurface()
    private var cacheKey: String? = null
    private var cacheEpoch = -1L

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = stateClass
    override fun getTextureLabel() = "V5 Skija"
    override fun textureIsReadyToBlit(state: T) = cacheEpoch == SkijaPIP.cacheEpoch && cacheKey == state.cacheKey
    override fun renderToTexture(state: T, poseStack: PoseStack) {
        cacheKey = null
        if (SkijaPIP.render(surface, state)) {
            cacheKey = state.cacheKey
            cacheEpoch = SkijaPIP.cacheEpoch
        }
    }

    override fun close() {
        surface.close()
        super.close()
    }
}
*///?} else {
internal fun createSkijaPIP(context: Context, pre: Boolean): PictureInPictureRenderer<*> =
    if (pre) SkijaPrePIPRenderer() else SkijaPIPRenderer()
internal fun createInventorySkijaPIP(context: Context): PictureInPictureRenderer<*> = CachedSkijaPIPRenderer(SkijaPIP.InventoryState::class.java)
internal fun createStatsSkijaPIP(context: Context): PictureInPictureRenderer<*> = CachedSkijaPIPRenderer(SkijaPIP.StatsState::class.java)
internal fun createPanelsSkijaPIP(context: Context): PictureInPictureRenderer<*> = CachedSkijaPIPRenderer(SkijaPIP.PanelsState::class.java)

private open class SkijaPIPRenderer : PictureInPictureRenderer<SkijaPIP.State>() {
    private val surface = SkijaSurface()

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = SkijaPIP.State::class.java
    override fun getTextureLabel() = "V5 Skija"
    override fun renderToTexture(state: SkijaPIP.State, poseStack: PoseStack, collector: SubmitNodeCollector) {
        SkijaPIP.render(
            surface,
            state,
            //? if >=26.3 {
            /*(this as PictureInPictureRendererAccessor).`ctjs$getTexture`(),
            *///?}
        )
    }

    override fun close() {
        surface.close()
        super.close()
    }
}

private class SkijaPrePIPRenderer : SkijaPIPRenderer() {
    @Suppress("UNCHECKED_CAST")
    override fun getRenderStateClass() = SkijaPIP.PreState::class.java as Class<SkijaPIP.State>
}

private class CachedSkijaPIPRenderer<T : SkijaPIP.CachedState>(private val stateClass: Class<T>) : PictureInPictureRenderer<T>() {
    private val surface = SkijaSurface()
    private var cacheKey: String? = null
    private var cacheEpoch = -1L

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = stateClass
    override fun getTextureLabel() = "V5 Skija"
    override fun textureIsReadyToBlit(state: T) = cacheEpoch == SkijaPIP.cacheEpoch && cacheKey == state.cacheKey
    override fun renderToTexture(state: T, poseStack: PoseStack, collector: SubmitNodeCollector) {
        cacheKey = null
        if (SkijaPIP.render(
            surface,
            state,
            //? if >=26.3 {
            /*(this as PictureInPictureRendererAccessor).`ctjs$getTexture`(),
            *///?}
        )) {
            cacheKey = state.cacheKey
            cacheEpoch = SkijaPIP.cacheEpoch
        }
    }

    override fun close() {
        surface.close()
        super.close()
    }
}
//?}
