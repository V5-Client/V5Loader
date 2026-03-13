package com.v5.render

import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.v5.screen.V5MainMenuScreen
import net.minecraft.client.gl.GlBackend
import net.minecraft.client.texture.GlTexture
import net.minecraft.client.MinecraftClient
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C

object ShaderUtils {
    private data class BackgroundShader(
        val displayName: String,
        val resourcePath: String
    )

    private data class ShaderProgram(
        val programId: Int,
        val vaoId: Int,
        val resolutionUniform: Int,
        val timeUniform: Int,
        val mouseUniform: Int
    )

    private val shaders = listOf(
        BackgroundShader("Contour Drift", "/assets/v5/shaders/background.fsh"),
        BackgroundShader("Crimson Bloom", "/assets/v5/shaders/background_crimson.fsh"),
        BackgroundShader("Neon Horizon", "/assets/v5/shaders/background_horizon.fsh")
    )

    private val vertexShaderSource = """
        #version 150 core

        const vec2 POSITIONS[3] = vec2[](
            vec2(-1.0, -1.0),
            vec2(3.0, -1.0),
            vec2(-1.0, 3.0)
        );

        void main() {
            gl_Position = vec4(POSITIONS[gl_VertexID], 0.0, 1.0);
        }
    """.trimIndent()

    private val programs = mutableMapOf<Int, ShaderProgram>()
    private val failedShaders = mutableSetOf<Int>()
    private var currentShaderIndex = 0
    private var wasPrimaryMouseDown = false
    private val startNanos = System.nanoTime()

    @JvmStatic
    fun renderBackground(mouseX: Double, mouseY: Double): Boolean {
        val shaderProgram = resolveActiveProgram() ?: return false

        val client = MinecraftClient.getInstance()
        val window = client.window
        val framebuffer = client.framebuffer

        val resolutionX = window.framebufferWidth.toFloat()
        val resolutionY = window.framebufferHeight.toFloat()
        val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0f

        handleHiddenShaderSwitch(mouseX.toFloat(), mouseY.toFloat())

        val previousFramebuffer = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING)
        val previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
        val previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)
        val previousViewport = IntArray(4)
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, previousViewport)

        val previousCullEnabled = GL11C.glIsEnabled(GL11C.GL_CULL_FACE)
        val previousBlendEnabled = GL11C.glIsEnabled(GL11C.GL_BLEND)
        val previousScissorEnabled = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST)
        val previousDepthTestEnabled = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST)
        val previousDepthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)

        val glFramebuffer = (framebuffer.colorAttachment as GlTexture).getOrCreateFramebuffer(
            (RenderSystem.getDevice() as GlBackend).bufferManager,
            null
        )

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, glFramebuffer)
        GlStateManager._viewport(0, 0, framebuffer.textureWidth, framebuffer.textureHeight)
        GlStateManager._colorMask(true, true, true, true)
        GlStateManager._disableCull()
        GlStateManager._disableScissorTest()
        GlStateManager._disableBlend()
        GlStateManager._disableDepthTest()
        GlStateManager._depthMask(false)

        GlStateManager._glUseProgram(shaderProgram.programId)
        GlStateManager._glBindVertexArray(shaderProgram.vaoId)

        if (shaderProgram.resolutionUniform >= 0) {
            GL20C.glUniform2f(shaderProgram.resolutionUniform, resolutionX, resolutionY)
        }
        if (shaderProgram.timeUniform >= 0) {
            GL20C.glUniform1f(shaderProgram.timeUniform, elapsedSeconds)
        }
        if (shaderProgram.mouseUniform >= 0) {
            GL20C.glUniform2f(shaderProgram.mouseUniform, mouseX.toFloat(), mouseY.toFloat())
        }

        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3)

        GlStateManager._glBindVertexArray(previousVao)
        GlStateManager._glUseProgram(previousProgram)
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previousFramebuffer)
        GlStateManager._viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])

        if (previousCullEnabled) GlStateManager._enableCull() else GlStateManager._disableCull()
        if (previousScissorEnabled) GlStateManager._enableScissorTest() else GlStateManager._disableScissorTest()
        if (previousBlendEnabled) GlStateManager._enableBlend() else GlStateManager._disableBlend()
        GlStateManager._depthMask(previousDepthMask)
        if (previousDepthTestEnabled) GlStateManager._enableDepthTest() else GlStateManager._disableDepthTest()
        return true
    }

    private fun resolveActiveProgram(): ShaderProgram? {
        ensureProgram(currentShaderIndex)?.let { return it }

        for (offset in 1 until shaders.size) {
            val nextIndex = (currentShaderIndex + offset) % shaders.size
            val shaderProgram = ensureProgram(nextIndex) ?: continue
            currentShaderIndex = nextIndex
            return shaderProgram
        }

        return null
    }

    private fun handleHiddenShaderSwitch(mouseX: Float, mouseY: Float) {
        val client = MinecraftClient.getInstance()
        if (client.currentScreen !is V5MainMenuScreen) {
            wasPrimaryMouseDown = false
            return
        }

        val primaryMouseDown = GLFW.glfwGetMouseButton(client.window.handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS
        val justClicked = primaryMouseDown && !wasPrimaryMouseDown
        wasPrimaryMouseDown = primaryMouseDown

        if (!justClicked || !isTitleHitbox(mouseX, mouseY, client.window.scaledWidth, client.window.scaledHeight)) {
            return
        }

        val originalIndex = currentShaderIndex
        repeat(shaders.size - 1) {
            val nextIndex = (currentShaderIndex + 1) % shaders.size
            currentShaderIndex = nextIndex
            if (ensureProgram(nextIndex) != null) return
        }

        currentShaderIndex = originalIndex
    }

    private fun isTitleHitbox(mouseX: Float, mouseY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val centerX = screenWidth / 2f
        val titleY = screenHeight / 2f - 74f
        val halfWidth = 86f
        val top = titleY - 6f
        val bottom = titleY + 38f
        return mouseX in (centerX - halfWidth)..(centerX + halfWidth) && mouseY in top..bottom
    }

    private fun ensureProgram(index: Int): ShaderProgram? {
        programs[index]?.let { return it }
        if (failedShaders.contains(index)) return null

        val shader = shaders[index]

        return try {
            val fragmentSource = loadFragmentShaderSource(shader.resourcePath)
            val vertexShader = compileShader(GL20C.GL_VERTEX_SHADER, vertexShaderSource, "vertex shader")
            val fragmentShader = compileShader(GL20C.GL_FRAGMENT_SHADER, fragmentSource, "fragment shader")

            val program = GlStateManager.glCreateProgram()
            GlStateManager.glAttachShader(program, vertexShader)
            GlStateManager.glAttachShader(program, fragmentShader)
            GlStateManager.glLinkProgram(program)

            val linkStatus = GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS)
            if (linkStatus == GL11C.GL_FALSE) {
                val log = GlStateManager.glGetProgramInfoLog(program, 2048)
                GlStateManager.glDeleteShader(vertexShader)
                GlStateManager.glDeleteShader(fragmentShader)
                GlStateManager.glDeleteProgram(program)
                throw IllegalStateException("Failed to link background shader program: $log")
            }

            GlStateManager.glDeleteShader(vertexShader)
            GlStateManager.glDeleteShader(fragmentShader)

            ShaderProgram(
                programId = program,
                vaoId = GL30C.glGenVertexArrays(),
                resolutionUniform = GL20C.glGetUniformLocation(program, "resolution"),
                timeUniform = GL20C.glGetUniformLocation(program, "time"),
                mouseUniform = GL20C.glGetUniformLocation(program, "mouse")
            ).also { programs[index] = it }
        } catch (t: Throwable) {
            failedShaders += index
            System.err.println("[V5] Failed to initialize background shader '${shader.displayName}': ${t.message}")
            t.printStackTrace()
            null
        }
    }

    private fun compileShader(type: Int, source: String, name: String): Int {
        val shaderId = GlStateManager.glCreateShader(type)
        GlStateManager.glShaderSource(shaderId, source)
        GlStateManager.glCompileShader(shaderId)

        val compileStatus = GlStateManager.glGetShaderi(shaderId, GL20C.GL_COMPILE_STATUS)
        if (compileStatus == GL11C.GL_FALSE) {
            val log = GlStateManager.glGetShaderInfoLog(shaderId, 2048)
            GlStateManager.glDeleteShader(shaderId)
            throw IllegalStateException("Failed to compile $name: $log")
        }

        return shaderId
    }

    private fun loadFragmentShaderSource(resourcePath: String): String {
        val stream = ShaderUtils::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find shader resource at $resourcePath")

        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
