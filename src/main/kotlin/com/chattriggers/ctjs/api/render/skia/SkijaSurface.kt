package com.chattriggers.ctjs.api.render.skia

import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.textures.GpuTexture
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL21C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL31C
import org.lwjgl.opengl.GL33C

internal class SkijaSurface : AutoCloseable {
    private var context: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var fbo = 0
    private var depthStencil = 0
    private var width = 0
    private var height = 0
    private var textureId = 0

    fun render(width: Int, height: Int, texture: GpuTexture, draw: (io.github.humbleui.skija.Canvas) -> Unit) {
        val colorTexture = texture as? GlTexture ?: return
        val state = GlState.capture()
        try {
            bindTarget(colorTexture.glId(), width, height)
            GlStateManager._viewport(0, 0, width, height)
            GL33C.glBindSampler(0, 0)
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0)
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, 0)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, 0)

            val directContext = context ?: DirectContext.makeGL().also { context = it }
            directContext.resetGLAll()
            val skijaSurface = surface(width, height, colorTexture.glId(), directContext)
            draw(skijaSurface.canvas)
            directContext.flushAndSubmit(skijaSurface, false)
        } finally {
            context?.resetGLAll()
            state.restore()
        }
    }

    private fun bindTarget(colorTexture: Int, width: Int, height: Int) {
        if (fbo == 0) fbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        GlStateManager._glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colorTexture, 0)
        if (depthStencil != 0 && this.width == width && this.height == height) return
        if (depthStencil != 0) GL30C.glDeleteRenderbuffers(depthStencil)
        depthStencil = GL30C.glGenRenderbuffers()
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depthStencil)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, width, height)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, depthStencil)
        this.width = width
        this.height = height
    }

    private fun surface(width: Int, height: Int, texture: Int, context: DirectContext): Surface {
        surface?.takeIf { it.width == width && it.height == height && textureId == texture }?.let { return it }
        surface?.close()
        renderTarget?.close()
        renderTarget = BackendRenderTarget.makeGL(width, height, 0, 8, fbo, GL30C.GL_RGBA8)
        return Surface.wrapBackendRenderTarget(context, renderTarget!!, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB())
            .also { surface = it; textureId = texture }
    }

    override fun close() {
        surface?.close()
        renderTarget?.close()
        if (depthStencil != 0) GL30C.glDeleteRenderbuffers(depthStencil)
        if (fbo != 0) GlStateManager._glDeleteFramebuffers(fbo)
        context?.close()
        surface = null
        renderTarget = null
        context = null
        depthStencil = 0
        fbo = 0
    }
}

private data class GlState(
    val readFramebuffer: Int,
    val drawFramebuffer: Int,
    val viewport: IntArray,
    val activeTexture: Int,
    val texture: Int,
    val sampler: Int,
    val program: Int,
    val vertexArray: Int,
    val arrayBuffer: Int,
    val scissorBox: IntArray,
    val blendSrcRgb: Int,
    val blendDstRgb: Int,
    val blendSrcAlpha: Int,
    val blendDstAlpha: Int,
    val blendEquationRgb: Int,
    val blendEquationAlpha: Int,
    val blend: Boolean,
    val cull: Boolean,
    val depth: Boolean,
    val stencil: Boolean,
    val scissor: Boolean,
    val primitiveRestart: Boolean,
    val depthMask: Boolean,
    val unpackBuffer: Int,
    val unpackAlignment: Int,
    val unpackRowLength: Int,
    val unpackSkipPixels: Int,
    val unpackSkipRows: Int,
) {
    fun restore() {
        GL20C.glUseProgram(program)
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0)
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture)
        GL33C.glBindSampler(0, sampler)
        GL13C.glActiveTexture(activeTexture)
        GL30C.glBindVertexArray(vertexArray)
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer)
        GL20C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
        GL14C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
        setEnabled(GL11C.GL_BLEND, blend)
        setEnabled(GL11C.GL_CULL_FACE, cull)
        setEnabled(GL11C.GL_DEPTH_TEST, depth)
        setEnabled(GL11C.GL_STENCIL_TEST, stencil)
        setEnabled(GL11C.GL_SCISSOR_TEST, scissor)
        setEnabled(GL31C.GL_PRIMITIVE_RESTART, primitiveRestart)
        GL11C.glDepthMask(depthMask)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer)
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, unpackAlignment)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, unpackRowLength)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, unpackSkipRows)
    }

    companion object {
        fun capture(): GlState {
            val activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0)
            return GlState(
                GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                ints(GL11C.GL_VIEWPORT, 4),
                activeTexture,
                GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),
                GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING),
                GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING),
                ints(GL11C.GL_SCISSOR_BOX, 4),
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),
                GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),
                GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA),
                GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
                GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA),
                GL11C.glIsEnabled(GL11C.GL_BLEND),
                GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
                GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                GL11C.glIsEnabled(GL31C.GL_PRIMITIVE_RESTART),
                GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING),
                GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT),
                GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH),
                GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_PIXELS),
                GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_ROWS),
            )
        }

        private fun ints(name: Int, size: Int) = IntArray(size).also { GL11C.glGetIntegerv(name, it) }
        private fun setEnabled(capability: Int, enabled: Boolean) =
            if (enabled) GL11C.glEnable(capability) else GL11C.glDisable(capability)
    }
}
