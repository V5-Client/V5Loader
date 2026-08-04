package com.chattriggers.ctjs.api.render.skia

import com.chattriggers.ctjs.api.render.Renderer
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f

internal open class SkijaPIP(bufferSource: MultiBufferSource.BufferSource) : PictureInPictureRenderer<SkijaPIP.State>(bufferSource) {
    private val surface = SkijaSurface()

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = State::class.java
    override fun getTextureLabel() = "V5 Skija"

    override fun renderToTexture(state: State, _poseStack: PoseStack) {
        val color = RenderSystem.outputColorTextureOverride ?: return
        surface.render(color.getWidth(0), color.getHeight(0), color.texture()) { canvas ->
            canvas.resetMatrix()
            canvas.scale(state.guiScale, state.guiScale)
            Renderer.beginSkijaFrame(canvas)
            try {
                state.callback.run()
            } finally {
                Renderer.endSkijaFrame()
            }
        }
    }

    override fun close() {
        surface.close()
        super.close()
    }

    open class State(
        private val width: Int,
        private val height: Int,
        val guiScale: Float,
        private val poseMatrix: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        private val area: ScreenRectangle,
        val callback: Runnable,
    ) : PictureInPictureRenderState {
        override fun x0() = 0
        override fun y0() = 0
        override fun x1() = width
        override fun y1() = height
        override fun scale() = 1f
        override fun pose() = poseMatrix
        override fun scissorArea() = scissor
        override fun bounds() = area
    }

    companion object {
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
                if (pre) PreState(width, height, scale, pose, scissor, bounds, callback)
                else State(width, height, scale, pose, scissor, bounds, callback),
            )
        }
    }

    class PreState(
        width: Int,
        height: Int,
        guiScale: Float,
        poseMatrix: Matrix3x2f,
        scissor: ScreenRectangle?,
        area: ScreenRectangle,
        callback: Runnable,
    ) : State(width, height, guiScale, poseMatrix, scissor, area, callback)
}

internal class SkijaPrePIP(bufferSource: MultiBufferSource.BufferSource) : SkijaPIP(bufferSource) {
    @Suppress("UNCHECKED_CAST")
    override fun getRenderStateClass() = SkijaPIP.PreState::class.java as Class<SkijaPIP.State>
}
