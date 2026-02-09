package com.v5.render

import com.mojang.blaze3d.systems.RenderSystem
import com.v5.render.helper.FrustumHolder
import com.v5.render.helper.FrustumUtils
import com.v5.render.objects.RenderLayers
import dev.quiteboring.swift.event.WorldRenderEvent
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.pow

object RenderUtils {
    private val client = MinecraftClient.getInstance()

    private val bufferSource = VertexConsumerProvider.immediate(BufferAllocator(2 * 1024 * 1024))

    private const val MAX_BOXES = 8192
    private const val MAX_LINES = 4096
    private const val MAX_TEXTS = 1024

    private val boxData = DoubleArray(MAX_BOXES * 6)
    private val boxColors = FloatArray(MAX_BOXES * 4)
    private val boxFlags = IntArray(MAX_BOXES)
    private var boxCount = 0

    private val lineData = DoubleArray(MAX_LINES * 6)
    private val lineColors = IntArray(MAX_LINES)
    private val lineFlags = IntArray(MAX_LINES)
    private var lineCount = 0

    private val textData = DoubleArray(MAX_TEXTS * 3)
    private val textStrings = arrayOfNulls<String>(MAX_TEXTS)
    private val textFlags = FloatArray(MAX_TEXTS * 4)
    private var textCount = 0

    private val tmpVector3f = Vector3f()

    private var cameraX = 0.0
    private var cameraY = 0.0
    private var cameraZ = 0.0
    private var cameraRotation: Quaternionf? = null

    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        val rf: Float get() = r / 255f
        val gf: Float get() = g / 255f
        val bf: Float get() = b / 255f
        val af: Float get() = a / 255f
        val packed: Int get() = (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)
    }

    init {
        WorldRenderEvent.LAST.register { context ->
            val localBoxCount = boxCount
            val localLineCount = lineCount
            val localTextCount = textCount

            boxCount = 0
            lineCount = 0
            textCount = 0

            if (localBoxCount == 0 && localLineCount == 0 && localTextCount == 0) return@register

            val matrices = context.matrixStack ?: return@register
            val camera = context.camera
            val frustum = FrustumHolder.currentFrustum

            cameraX = camera.pos.x
            cameraY = camera.pos.y
            cameraZ = camera.pos.z
            cameraRotation = camera.rotation

            matrices.push()
            matrices.translate(-cameraX.toFloat(), -cameraY.toFloat(), -cameraZ.toFloat())

            if (localBoxCount > 0) {
                renderBoxBatch(matrices, frustum, localBoxCount)
            }

            if (localLineCount > 0) {
                renderLineBatch(matrices, frustum, localLineCount)
            }

            matrices.pop()

            if (localTextCount > 0) {
                renderTextBatch(matrices, localTextCount)
            }

            bufferSource.draw()
            RenderSystem.lineWidth(1.0f)
        }
    }

    private fun renderBoxBatch(matrices: MatrixStack, frustum: Frustum?, count: Int) {
        val indices = (0 until count).sortedWith { a, b ->
            val aFlags = boxFlags[a]
            val bFlags = boxFlags[b]
            val aFilled = (aFlags and 1) != 0
            val bFilled = (bFlags and 1) != 0
            if (aFilled != bFilled) return@sortedWith if (bFilled) 1 else -1

            val aDepth = (aFlags and 2) != 0
            val bDepth = (bFlags and 2) != 0
            bDepth.compareTo(aDepth)
        }

        var currentLayer: net.minecraft.client.render.RenderLayer? = null
        var currentLineWidth = -1f

        for (idx in indices) {
            val flags = boxFlags[idx]
            val visible = (flags and 4) != 0
            if (!visible) continue

            val i = idx * 6
            val ci = idx * 4

            val filled = (flags and 1) != 0
            val depth = (flags and 2) != 0
            val layer = when {
                filled && depth -> RenderLayers.TRIANGLE_STRIP
                filled -> RenderLayers.TRIANGLE_STRIP_ESP
                depth -> RenderLayers.LINE_LIST
                else -> RenderLayers.LINE_LIST_ESP
            }

            if (layer != currentLayer) {
                bufferSource.draw()
                currentLayer = layer
                currentLineWidth = -1f
            }

            if (!filled) {
                val thickness = ((flags shr 3) and 0xFF) / 10f
                val dist = distanceSquared(
                    (boxData[i] + boxData[i + 3]) * 0.5,
                    (boxData[i + 1] + boxData[i + 4]) * 0.5,
                    (boxData[i + 2] + boxData[i + 5]) * 0.5
                )
                val scaledWidth = (thickness / dist.coerceAtLeast(1.0).pow(0.1)).toFloat()

                if (abs(scaledWidth - currentLineWidth) > 0.05f) {
                    bufferSource.draw()
                    RenderSystem.lineWidth(scaledWidth)
                    currentLineWidth = scaledWidth
                }
            }

            val buffer = bufferSource.getBuffer(layer)

            if (filled) {
                VertexRendering.drawFilledBox(
                    matrices, buffer,
                    boxData[i].toFloat(), boxData[i + 1].toFloat(), boxData[i + 2].toFloat(),
                    boxData[i + 3].toFloat(), boxData[i + 4].toFloat(), boxData[i + 5].toFloat(),
                    boxColors[ci], boxColors[ci + 1], boxColors[ci + 2], boxColors[ci + 3]
                )
            } else {
                VertexRendering.drawBox(
                    matrices.peek(), buffer,
                    boxData[i], boxData[i + 1], boxData[i + 2],
                    boxData[i + 3], boxData[i + 4], boxData[i + 5],
                    boxColors[ci], boxColors[ci + 1], boxColors[ci + 2], boxColors[ci + 3]
                )
            }
        }

        bufferSource.draw()
    }

    private fun renderLineBatch(matrices: MatrixStack, frustum: Frustum?, count: Int) {
        var currentLayer: net.minecraft.client.render.RenderLayer? = null
        var currentLineWidth = -1f

        for (idx in 0 until count) {
            val i = idx * 6
            val flags = lineFlags[idx]
            val depth = (flags and 1) != 0
            val isTracer = (flags and 2) != 0

            if (!isTracer) {
                val minX = kotlin.math.min(lineData[i], lineData[i + 3])
                val minY = kotlin.math.min(lineData[i + 1], lineData[i + 4])
                val minZ = kotlin.math.min(lineData[i + 2], lineData[i + 5])
                val maxX = kotlin.math.max(lineData[i], lineData[i + 3])
                val maxY = kotlin.math.max(lineData[i + 1], lineData[i + 4])
                val maxZ = kotlin.math.max(lineData[i + 2], lineData[i + 5])

                if (!FrustumUtils.isVisible(frustum, minX, minY, minZ, maxX, maxY, maxZ)) continue
            }

            val layer = if (depth) RenderLayers.LINE_LIST else RenderLayers.LINE_LIST_ESP

            val thickness = ((flags shr 2) and 0xFF) / 10f
            val dist = distanceSquared(lineData[i], lineData[i + 1], lineData[i + 2])
            val scaledWidth = (kotlin.math.round((thickness / dist.coerceAtLeast(1.0).pow(0.1)).toFloat() * 10f) / 10f).coerceAtLeast(0.1f)

            if (layer != currentLayer || abs(scaledWidth - currentLineWidth) > 0.01f) {
                bufferSource.draw()

                currentLayer = layer
                currentLineWidth = scaledWidth

                RenderSystem.lineWidth(scaledWidth)
            }

            val buffer = bufferSource.getBuffer(layer)

            tmpVector3f.set(lineData[i].toFloat(), lineData[i + 1].toFloat(), lineData[i + 2].toFloat())
            val dir = Vec3d(
                lineData[i + 3] - lineData[i],
                lineData[i + 4] - lineData[i + 1],
                lineData[i + 5] - lineData[i + 2]
            )

            VertexRendering.drawVector(matrices, buffer, tmpVector3f, dir, lineColors[idx])
        }

        bufferSource.draw()
    }

    private fun renderTextBatch(matrices: MatrixStack, count: Int) {
        val frustum = FrustumHolder.currentFrustum

        for (idx in 0 until count) {
            val i = idx * 3
            val x = textData[i]
            val y = textData[i + 1]
            val z = textData[i + 2]

            if (!FrustumUtils.isVisible(frustum, x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5)) continue

            val fi = idx * 4
            val scale = textFlags[fi]
            val width = textFlags[fi + 1]
            val flags = textFlags[fi + 2].toInt()
            val seeThrough = (flags and 1) != 0
            val backgroundBox = (flags and 2) != 0
            val increase = (flags and 4) != 0
            val translate = (flags and 8) != 0

            matrices.push()

            val tx = (x - cameraX).toFloat()
            val ty = (y - cameraY).toFloat()
            val tz = (z - cameraZ).toFloat()

            if (translate) matrices.translate(tx, ty, tz)

            cameraRotation?.let { matrices.multiply(it) }

            val s = if (increase) {
                scale * (kotlin.math.sqrt((tx * tx + ty * ty + tz * tz).toDouble()).toFloat() / 120f).coerceAtLeast(0.01f)
            } else {
                scale * 0.025f
            }

            matrices.scale(s, -s, s)

            val layer = if (seeThrough) TextRenderer.TextLayerType.SEE_THROUGH else TextRenderer.TextLayerType.NORMAL
            val text = textStrings[idx] ?: ""
            client.textRenderer.draw(
                text,
                -width / 2f,
                0f,
                -1,
                backgroundBox,
                matrices.peek().positionMatrix,
                bufferSource,
                layer,
                0,
                15728880
            )

            matrices.pop()
        }
    }

    private fun distanceSquared(x: Double, y: Double, z: Double): Double {
        val dx = x - cameraX
        val dy = y - cameraY
        val dz = z - cameraZ
        return dx * dx + dy * dy + dz * dz
    }

    @JvmStatic
    fun drawFilledBox(pos: Vec3d, color: Color, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        addBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, color, true, depth, 10f)
    }

    @JvmStatic
    fun drawWireFrameBox(pos: Vec3d, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        addBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, color, false, depth, thickness)
    }

    @JvmStatic
    fun drawStyledBox(pos: Vec3d, color1: Color, color2: Color, wireThickness: Float = 5f, depth: Boolean = false) {
        if (boxCount < MAX_BOXES) addBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, color1, true, depth, 10f)
        if (boxCount < MAX_BOXES) addBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, color2, false, depth, wireThickness)
    }

    @JvmStatic
    fun drawSizedBox(pos: Vec3d, width: Double, height: Double, length: Double, color: Color, filled: Boolean = true, thickness: Float = 1f, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        val hw = width * 0.5
        val hl = length * 0.5
        addBox(pos.x - hw, pos.y, pos.z - hl, pos.x + hw, pos.y + height, pos.z + hl, color, filled, depth, thickness)
    }

    @JvmStatic
    fun drawHitbox(entity: Entity, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val b = entity.boundingBox
        if (boxCount < MAX_BOXES) addBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, color, true, depth, 10f)
        if (boxCount < MAX_BOXES) addBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, color, false, depth, thickness)
    }

    @JvmStatic
    fun drawLine(start: Vec3d, end: Vec3d, color: Color, thickness: Float = 3f, depth: Boolean = false) {
        if (lineCount >= MAX_LINES) return
        addLine(start.x, start.y, start.z, end.x, end.y, end.z, color.packed, thickness, depth, false)
    }

    @JvmStatic
    fun drawTracer(targetPos: Vec3d, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        if (lineCount >= MAX_LINES) return
        val camera = client.gameRenderer.camera
        val lookVec = Vec3d.fromPolar(camera.pitch, camera.yaw)
        val start = camera.pos.add(lookVec.multiply(0.1))
        addLine(start.x, start.y, start.z, targetPos.x, targetPos.y, targetPos.z, color.packed, thickness, depth, true)
    }

    @JvmStatic
    fun drawText(text: String, pos: Vec3d, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        if (textCount >= MAX_TEXTS) return
        val i = textCount * 3
        val fi = textCount * 4

        textData[i] = pos.x
        textData[i + 1] = pos.y
        textData[i + 2] = pos.z

        textStrings[textCount] = text
        textFlags[fi] = scale
        textFlags[fi + 1] = client.textRenderer.getWidth(text).toFloat()
        textFlags[fi + 2] = ((if (seeThrough) 1 else 0) or
                (if (backgroundBox) 2 else 0) or
                (if (increase) 4 else 0) or
                (if (translate) 8 else 0)).toFloat()

        textCount++
    }

    private fun addBox(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
                       color: Color, filled: Boolean, depth: Boolean, thickness: Float) {
        if (!FrustumUtils.isVisible(FrustumHolder.currentFrustum, x1, y1, z1, x2, y2, z2)) return

        val i = boxCount * 6
        val ci = boxCount * 4

        boxData[i] = x1; boxData[i + 1] = y1; boxData[i + 2] = z1
        boxData[i + 3] = x2; boxData[i + 4] = y2; boxData[i + 5] = z2

        boxColors[ci] = color.rf; boxColors[ci + 1] = color.gf
        boxColors[ci + 2] = color.bf; boxColors[ci + 3] = color.af

        boxFlags[boxCount] = (if (filled) 1 else 0) or
                (if (depth) 2 else 0) or
                4 or // visible
                ((thickness * 10).toInt().coerceIn(0, 255) shl 3)

        boxCount++
    }

    private fun addLine(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
                        argb: Int, thickness: Float, depth: Boolean, isTracer: Boolean) {
        val i = lineCount * 6

        lineData[i] = x1; lineData[i + 1] = y1; lineData[i + 2] = z1
        lineData[i + 3] = x2; lineData[i + 4] = y2; lineData[i + 5] = z2

        lineColors[lineCount] = argb
        lineFlags[lineCount] = (if (depth) 1 else 0) or
                (if (isTracer) 2 else 0) or
                ((thickness * 10).toInt().coerceIn(0, 255) shl 2)

        lineCount++
    }
}