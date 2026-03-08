package com.v5.render

import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.logging.LogUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.io.File
import java.util.zip.ZipFile

object ShaderUtils {
    private val logger = LogUtils.getLogger()
    private const val SHADER_CLASSPATH = "assets/v5/shaders/background.fsh"

    private const val VERTEX_SOURCE = """
        #version 150 core

        void main() {
            vec2 pos = vec2((gl_VertexID == 2) ? 3.0 : -1.0, (gl_VertexID == 1) ? 3.0 : -1.0);
            gl_Position = vec4(pos, 0.0, 1.0);
        }
    """

    private var programId = 0
    private var vaoId = 0
    private var startTimeNanos = System.nanoTime()
    private var nextRetryTimeMs = 0L

    fun renderBackground(width: Int, height: Int, mouseX: Double, mouseY: Double): Boolean {
        if (width <= 0 || height <= 0) return false
        ensureInitialized()
        if (programId == 0) return false

        GL11.glViewport(0, 0, width, height)
        GlStateManager._disableBlend()
        GlStateManager._disableDepthTest()
        GlStateManager._depthMask(false)
        GlStateManager._disableCull()

        GL20.glUseProgram(programId)

        val timeSeconds = ((System.nanoTime() - startTimeNanos).toDouble() / 1_000_000_000.0).toFloat()
        setUniform2f("resolution", width.toFloat(), height.toFloat())
        setUniform1f("time", timeSeconds)
        setUniform2f("mouse", mouseX.toFloat(), mouseY.toFloat())

        GL30.glBindVertexArray(vaoId)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
        GL30.glBindVertexArray(0)
        GL20.glUseProgram(0)

        GlStateManager._enableCull()
        GlStateManager._depthMask(true)
        GlStateManager._enableDepthTest()
        return true
    }

    private fun ensureInitialized() {
        if (programId != 0) return
        val now = System.currentTimeMillis()
        if (now < nextRetryTimeMs) return

        try {
            val fragmentSource = loadFragmentSource()
            if (fragmentSource.isEmpty()) {
                nextRetryTimeMs = now + 1_000L
                return
            }

            val vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SOURCE)
            val fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource)

            val program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            GL20.glLinkProgram(program)

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                val infoLog = GL20.glGetProgramInfoLog(program)
                GL20.glDeleteShader(vertexShader)
                GL20.glDeleteShader(fragmentShader)
                GL20.glDeleteProgram(program)
                throw IllegalStateException("Shader link failed: $infoLog")
            }

            GL20.glDetachShader(program, vertexShader)
            GL20.glDetachShader(program, fragmentShader)
            GL20.glDeleteShader(vertexShader)
            GL20.glDeleteShader(fragmentShader)

            val vao = GL30.glGenVertexArrays()
            if (vao == 0) {
                GL20.glDeleteProgram(program)
                throw IllegalStateException("Failed to create VAO for background shader.")
            }

            programId = program
            vaoId = vao
            startTimeNanos = System.nanoTime()
            nextRetryTimeMs = 0L
        } catch (t: Throwable) {
            nextRetryTimeMs = now + 1_000L
            logger.error("Failed to initialize V5 background shader", t)
        }
    }

    private fun setUniform1f(name: String, value: Float) {
        val location = GL20.glGetUniformLocation(programId, name)
        if (location != -1) {
            GL20.glUniform1f(location, value)
        }
    }

    private fun setUniform2f(name: String, x: Float, y: Float) {
        val location = GL20.glGetUniformLocation(programId, name)
        if (location != -1) {
            GL20.glUniform2f(location, x, y)
        }
    }

    private fun loadFragmentSource(): String {
        val directPaths = listOf(
            SHADER_CLASSPATH,
            "/$SHADER_CLASSPATH",
            "/assets/v5/shaders/background.fsh"
        )

        val classLoaders = listOfNotNull(Thread.currentThread().contextClassLoader, ShaderUtils::class.java.classLoader)

        for (loader in classLoaders) {
            for (path in directPaths) {
                val cleanPath = if (path.startsWith("/")) path.substring(1) else path
                val stream = loader.getResourceAsStream(cleanPath) ?: ShaderUtils::class.java.getResourceAsStream(path) ?: continue
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    return reader.readText()
                }
            }
        }

        val codeSourcePath = ShaderUtils::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
        if (codeSourcePath != null) {
            if (codeSourcePath.isFile && codeSourcePath.extension.equals("jar", ignoreCase = true)) {
                ZipFile(codeSourcePath).use { zip ->
                    val entry = zip.getEntry(SHADER_CLASSPATH)
                    if (entry != null) {
                        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                            return reader.readText()
                        }
                    }
                }
            } else if (codeSourcePath.isDirectory) {
                val shaderFile = File(codeSourcePath, SHADER_CLASSPATH)
                if (shaderFile.exists()) {
                    return shaderFile.readText(Charsets.UTF_8)
                }
            }
        }

        logger.error("Could not find fragment shader on classpath: {}", SHADER_CLASSPATH)
        return ""
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val infoLog = GL20.glGetShaderInfoLog(shader)
            GL20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $infoLog")
        }

        return shader
    }
}
