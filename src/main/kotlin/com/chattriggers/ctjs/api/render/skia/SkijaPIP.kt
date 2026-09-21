package com.chattriggers.ctjs.api.render.skia

import com.chattriggers.ctjs.api.render.Render2D
//? if <26.3 {
import com.mojang.blaze3d.systems.RenderSystem
//?} else {
/*import com.mojang.renderpearl.api.textures.GpuTexture
*///?}
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor

internal object SkijaPIP {
    var cacheEpoch = 0L

    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor, callback: Runnable, pre: Boolean = false) {
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val pose = Matrix3x2f(graphics.pose())
        val screen = ScreenRectangle(0, 0, width, height).transformMaxBounds(pose)
        val scissor = graphics.scissorStack.peek()
        val bounds = scissor?.intersection(screen) ?: screen
        if (bounds.width <= 0 || bounds.height <= 0) return
        val scale = net.minecraft.client.Minecraft.getInstance().window.guiScale.toFloat()
        graphics.guiRenderState.addPicturesInPictureState(
            if (pre) PreState(0, 0, width, height, scale, pose, scissor, bounds, callback)
            else State(0, 0, width, height, scale, pose, scissor, bounds, callback),
        )
    }

    @JvmStatic
    fun drawInventory(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, key: String, callback: Runnable) =
        drawCached(graphics, x, y, width, height, key, callback, ::InventoryState)

    @JvmStatic
    fun drawStats(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, key: String, callback: Runnable) =
        drawCached(graphics, x, y, width, height, key, callback, ::StatsState)

    @JvmStatic
    fun drawPanels(
        graphics: GuiGraphicsExtractor,
        x: Float, y: Float, width: Float, height: Float,
        clipX: Float, clipY: Float, clipWidth: Float, clipHeight: Float,
        key: String, callback: Runnable,
    ) = drawCached(graphics, x, y, width, height, key, callback, ::PanelsState, clipX, clipY, clipWidth, clipHeight)

    private fun <T : CachedState> drawCached(
        graphics: GuiGraphicsExtractor,
        x: Float, y: Float, width: Float, height: Float,
        key: String, callback: Runnable,
        state: (Int, Int, Int, Int, Float, Matrix3x2f, ScreenRectangle?, ScreenRectangle, String, Runnable) -> T,
        clipX: Float = x, clipY: Float = y, clipWidth: Float = width, clipHeight: Float = height,
    ) {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = ceil(x + width).toInt()
        val y1 = ceil(y + height).toInt()
        val pose = Matrix3x2f(graphics.pose())
        val bounds = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)
        val clipX0 = floor(clipX).toInt()
        val clipY0 = floor(clipY).toInt()
        val clip = ScreenRectangle(clipX0, clipY0, ceil(clipX + clipWidth).toInt() - clipX0, ceil(clipY + clipHeight).toInt() - clipY0)
            .transformMaxBounds(pose)
            .intersection(ScreenRectangle(0, 0, graphics.guiWidth(), graphics.guiHeight())) ?: return
        val scissor = graphics.scissorStack.peek()?.intersection(clip) ?: run {
            if (graphics.scissorStack.peek() != null) return
            clip
        }
        val area = bounds.intersection(scissor) ?: return
        if (area.width <= 0 || area.height <= 0) return
        val scale = net.minecraft.client.Minecraft.getInstance().window.guiScale.toFloat()
        graphics.guiRenderState.addPicturesInPictureState(state(x0, y0, x1, y1, scale, pose, scissor, area, key, callback))
    }

    @JvmStatic
    fun render(
        surface: SkijaSurface,
        state: State,
        //? if >=26.3 {
        /*color: GpuTexture,
        *///?}
    ): Boolean {
        //? if <26.3 {
        val color = RenderSystem.outputColorTextureOverride ?: return false
        return surface.render(color.getWidth(0), color.getHeight(0), color.texture()) { canvas ->
        //?} else {
        /*return surface.render(color.getWidth(0), color.getHeight(0), color) { canvas ->
        *///?}
            canvas.resetMatrix()
            canvas.scale(state.guiScale, state.guiScale)
            canvas.translate(-state.x0().toFloat(), -state.y0().toFloat())
            Render2D.beginSkijaFrame(canvas)
            try {
                state.callback.run()
            } finally {
                Render2D.endSkijaFrame()
            }
        }
    }

    open class State(
        private val x0: Int,
        private val y0: Int,
        private val x1: Int,
        private val y1: Int,
        val guiScale: Float,
        private val poseMatrix: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        private val area: ScreenRectangle,
        val callback: Runnable,
    ) : PictureInPictureRenderState {
        override fun x0() = x0
        override fun y0() = y0
        override fun x1() = x1
        override fun y1() = y1
        override fun scale() = 1f
        override fun pose() = poseMatrix
        override fun scissorArea() = scissor
        override fun bounds() = area
    }

    class PreState(
        x0: Int, y0: Int, x1: Int, y1: Int,
        guiScale: Float,
        poseMatrix: Matrix3x2f,
        scissor: ScreenRectangle?,
        area: ScreenRectangle,
        callback: Runnable,
    ) : State(x0, y0, x1, y1, guiScale, poseMatrix, scissor, area, callback)

    open class CachedState(
        x0: Int, y0: Int, x1: Int, y1: Int,
        guiScale: Float, poseMatrix: Matrix3x2f, scissor: ScreenRectangle?, area: ScreenRectangle,
        val cacheKey: String,
        callback: Runnable,
    ) : State(x0, y0, x1, y1, guiScale, poseMatrix, scissor, area, callback)

    class InventoryState(x0: Int, y0: Int, x1: Int, y1: Int, guiScale: Float, pose: Matrix3x2f, scissor: ScreenRectangle?, area: ScreenRectangle, key: String, callback: Runnable) :
        CachedState(x0, y0, x1, y1, guiScale, pose, scissor, area, key, callback)

    class StatsState(x0: Int, y0: Int, x1: Int, y1: Int, guiScale: Float, pose: Matrix3x2f, scissor: ScreenRectangle?, area: ScreenRectangle, key: String, callback: Runnable) :
        CachedState(x0, y0, x1, y1, guiScale, pose, scissor, area, key, callback)

    class PanelsState(x0: Int, y0: Int, x1: Int, y1: Int, guiScale: Float, pose: Matrix3x2f, scissor: ScreenRectangle?, area: ScreenRectangle, key: String, callback: Runnable) :
        CachedState(x0, y0, x1, y1, guiScale, pose, scissor, area, key, callback)
}
