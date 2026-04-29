package com.v5.render

import com.v5.render.helper.FrustumHolder
import com.v5.render.helper.FrustumUtils
import com.v5.render.objects.RenderLayers
import com.v5.event.WorldRenderEvent
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object RenderUtils {
    private val client = MinecraftClient.getInstance()
    private const val INV_255 = 1f / 255f

    private val bufferSource = VertexConsumerProvider.immediate(BufferAllocator(2 * 1024 * 1024))

    private const val MAX_BOXES = 8192
    private const val MAX_LINES = 4096
    private const val MAX_TEXTS = 1024
    private const val BOX_KEY_COUNT = 514 // filled(depth/no depth) + wire(depth 0-255 thickness, no-depth 0-255 thickness)
    private const val LINE_KEY_COUNT = 512 // depth(0-255 thickness) + no-depth(0-255 thickness)
    private const val FULL_BRIGHT = 15728880

    private val boxData = DoubleArray(MAX_BOXES * 6)
    private val boxColors = IntArray(MAX_BOXES)
    private val boxFlags = IntArray(MAX_BOXES)
    private val boxOrder = IntArray(MAX_BOXES)
    private val boxKeyCounts = IntArray(BOX_KEY_COUNT)
    private val boxKeyOffsets = IntArray(BOX_KEY_COUNT)
    private val boxKeyWrite = IntArray(BOX_KEY_COUNT)
    private var boxCount = 0

    private val lineData = DoubleArray(MAX_LINES * 6)
    private val lineColors = IntArray(MAX_LINES)
    private val lineFlags = IntArray(MAX_LINES)
    private val lineOrder = IntArray(MAX_LINES)
    private val lineVisibleIndices = IntArray(MAX_LINES)
    private val lineVisibleKeys = IntArray(MAX_LINES)
    private val lineKeyCounts = IntArray(LINE_KEY_COUNT)
    private val lineKeyOffsets = IntArray(LINE_KEY_COUNT)
    private val lineKeyWrite = IntArray(LINE_KEY_COUNT)
    private var lineCount = 0

    private val textData = DoubleArray(MAX_TEXTS * 3)
    private val textStrings = arrayOfNulls<String>(MAX_TEXTS)
    private val textScales = FloatArray(MAX_TEXTS)
    private val textWidths = FloatArray(MAX_TEXTS)
    private val textFlags = IntArray(MAX_TEXTS)
    private var textCount = 0

    private var cameraX = 0.0
    private var cameraY = 0.0
    private var cameraZ = 0.0
    private var cameraRotation: Quaternionf? = null

    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        val rf: Float = r * INV_255
        val gf: Float = g * INV_255
        val bf: Float = b * INV_255
        val af: Float = a * INV_255
        val packed: Int = (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)
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

            cameraX = camera.cameraPos.x
            cameraY = camera.cameraPos.y
            cameraZ = camera.cameraPos.z
            cameraRotation = camera.rotation

            matrices.push()
            matrices.translate(-cameraX.toFloat(), -cameraY.toFloat(), -cameraZ.toFloat())

            if (localBoxCount > 0) {
                renderBoxBatch(matrices, localBoxCount)
            }

            if (localLineCount > 0) {
                renderLineBatch(matrices, frustum, localLineCount)
            }

            matrices.pop()

            if (localTextCount > 0) {
                renderTextBatch(matrices, localTextCount)
            }

            bufferSource.draw()

            // FIXME
//            RenderSystem.lineWidth(1.0f)
        }
    }

    private fun boxKey(flags: Int): Int {
        val filled = (flags and 1) != 0
        val depth = (flags and 2) != 0
        if (filled) return if (depth) 0 else 1
        val thickness = (flags ushr 3) and 0xFF
        return if (depth) 2 + thickness else 2 + 256 + thickness
    }

    private fun lineKey(flags: Int): Int {
        val depth = (flags and 1) != 0
        val thickness = (flags ushr 2) and 0xFF
        return if (depth) thickness else 256 + thickness
    }

    private fun buildBoxOrder(count: Int): Int {
        java.util.Arrays.fill(boxKeyCounts, 0)

        var total = 0
        for (idx in 0 until count) {
            val flags = boxFlags[idx]
            boxKeyCounts[boxKey(flags)]++
            total++
        }

        if (total == 0) return 0

        var offset = 0
        for (k in 0 until BOX_KEY_COUNT) {
            boxKeyOffsets[k] = offset
            boxKeyWrite[k] = offset
            offset += boxKeyCounts[k]
        }

        for (idx in 0 until count) {
            val flags = boxFlags[idx]
            val key = boxKey(flags)
            boxOrder[boxKeyWrite[key]++] = idx
        }

        return total
    }

    private fun buildLineOrder(frustum: Frustum?, count: Int): Int {
        java.util.Arrays.fill(lineKeyCounts, 0)

        var visibleCount = 0
        for (idx in 0 until count) {
            val i = idx * 6
            val flags = lineFlags[idx]
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

            val key = lineKey(flags)
            lineVisibleIndices[visibleCount] = idx
            lineVisibleKeys[visibleCount] = key
            lineKeyCounts[key]++
            visibleCount++
        }

        if (visibleCount == 0) return 0

        var offset = 0
        for (k in 0 until LINE_KEY_COUNT) {
            lineKeyOffsets[k] = offset
            lineKeyWrite[k] = offset
            offset += lineKeyCounts[k]
        }

        for (visibleIdx in 0 until visibleCount) {
            val key = lineVisibleKeys[visibleIdx]
            lineOrder[lineKeyWrite[key]++] = lineVisibleIndices[visibleIdx]
        }

        return visibleCount
    }

    private fun renderBoxBatch(matrices: MatrixStack, count: Int) {
        val visibleCount = buildBoxOrder(count)
        if (visibleCount == 0) return

        var currentLayer: net.minecraft.client.render.RenderLayer? = null
        var currentLineWidth = -1f
        val entry = matrices.peek()

        for (key in 0 until BOX_KEY_COUNT) {
            val start = boxKeyOffsets[key]
            val end = if (key == BOX_KEY_COUNT - 1) visibleCount else boxKeyOffsets[key + 1]
            if (start >= end) continue

            val filled = key < 2
            val depth = if (filled) key == 0 else key < (2 + 256)
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
                val thicknessRaw = if (depth) key - 2 else key - (2 + 256)
                val lineWidth = (thicknessRaw / 10f).coerceAtLeast(0.1f)
                if (lineWidth != currentLineWidth) {
                    bufferSource.draw()
                    // FIXME
//                    RenderSystem.lineWidth(lineWidth)
                    currentLineWidth = lineWidth
                }
            }

            val buffer = bufferSource.getBuffer(layer)
            for (pos in start until end) {
                val idx = boxOrder[pos]
                val i = idx * 6
                val color = boxColors[idx]
                val x1 = boxData[i] - cameraX
                val y1 = boxData[i + 1] - cameraY
                val z1 = boxData[i + 2] - cameraZ
                val x2 = boxData[i + 3] - cameraX
                val y2 = boxData[i + 4] - cameraY
                val z2 = boxData[i + 5] - cameraZ

                if (filled) {
                    // FIXME
//                    VertexRendering.drawFilledBox(
//                        matrices, buffer,
//                        boxData[i].toFloat(), boxData[i + 1].toFloat(), boxData[i + 2].toFloat(),
//                        boxData[i + 3].toFloat(), boxData[i + 4].toFloat(), boxData[i + 5].toFloat(),
//                        boxColors[ci], boxColors[ci + 1], boxColors[ci + 2], boxColors[ci + 3]
//                    )
                } else {
                    // FIXME
//                    VertexRendering.drawBox(
//                        entry, buffer,
//                        boxData[i], boxData[i + 1], boxData[i + 2],
//                        boxData[i + 3], boxData[i + 4], boxData[i + 5],
//                        boxColors[ci], boxColors[ci + 1], boxColors[ci + 2], boxColors[ci + 3]
//                    )
                }
            }
        }

        bufferSource.draw()
    }

    private fun renderLineBatch(matrices: MatrixStack, frustum: Frustum?, count: Int) {
        val visibleCount = buildLineOrder(frustum, count)
        if (visibleCount == 0) return

        var currentLayer: net.minecraft.client.render.RenderLayer? = null
        var currentLineWidth = -1f
        val entry = matrices.peek()

        for (key in 0 until LINE_KEY_COUNT) {
            val start = lineKeyOffsets[key]
            val end = if (key == LINE_KEY_COUNT - 1) visibleCount else lineKeyOffsets[key + 1]
            if (start >= end) continue

            val depth = key < 256
            val layer = if (depth) RenderLayers.LINE_LIST else RenderLayers.LINE_LIST_ESP
            val lineWidth = ((key and 0xFF) / 10f).coerceAtLeast(0.1f)

            if (layer != currentLayer || lineWidth != currentLineWidth) {
                bufferSource.draw()
                currentLayer = layer
                currentLineWidth = lineWidth
                // FIXME
//                RenderSystem.lineWidth(lineWidth)
            }

            val buffer = bufferSource.getBuffer(layer)
            for (pos in start until end) {
                val idx = lineOrder[pos]
                val i = idx * 6
                val x1 = lineData[i] - cameraX
                val y1 = lineData[i + 1] - cameraY
                val z1 = lineData[i + 2] - cameraZ
                val x2 = lineData[i + 3] - cameraX
                val y2 = lineData[i + 4] - cameraY
                val z2 = lineData[i + 5] - cameraZ

                writeLine(
                    entry,
                    buffer,
                    x1, y1, z1,
                    x2, y2, z2,
                    lineColors[idx],
                    lineWidth
                )
            }
        }

        bufferSource.draw()
    }

    private fun writeFilledBox(
        entry: MatrixStack.Entry,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        argb: Int
    ) {
        val minX = min(x1, x2).toFloat()
        val minY = min(y1, y2).toFloat()
        val minZ = min(z1, z2).toFloat()
        val maxX = max(x1, x2).toFloat()
        val maxY = max(y1, y2).toFloat()
        val maxZ = max(z1, z2).toFloat()

        quad(buffer, entry, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, argb)
        quad(buffer, entry, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, argb)
        quad(buffer, entry, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, argb)
        quad(buffer, entry, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, argb)
        quad(buffer, entry, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, argb)
        quad(buffer, entry, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, argb)
    }

    private fun quad(
        buffer: VertexConsumer,
        entry: MatrixStack.Entry,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        argb: Int
    ) {
        buffer.vertex(entry, x1, y1, z1).color(argb)
        buffer.vertex(entry, x2, y2, z2).color(argb)
        buffer.vertex(entry, x3, y3, z3).color(argb)
        buffer.vertex(entry, x4, y4, z4).color(argb)
    }

    private fun writeBox(
        entry: MatrixStack.Entry,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        argb: Int,
        lineWidth: Float
    ) {
        val minX = min(x1, x2)
        val minY = min(y1, y2)
        val minZ = min(z1, z2)
        val maxX = max(x1, x2)
        val maxY = max(y1, y2)
        val maxZ = max(z1, z2)

        writeLine(entry, buffer, minX, minY, minZ, maxX, minY, minZ, argb, lineWidth)
        writeLine(entry, buffer, maxX, minY, minZ, maxX, minY, maxZ, argb, lineWidth)
        writeLine(entry, buffer, maxX, minY, maxZ, minX, minY, maxZ, argb, lineWidth)
        writeLine(entry, buffer, minX, minY, maxZ, minX, minY, minZ, argb, lineWidth)

        writeLine(entry, buffer, minX, maxY, minZ, maxX, maxY, minZ, argb, lineWidth)
        writeLine(entry, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, argb, lineWidth)
        writeLine(entry, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, argb, lineWidth)
        writeLine(entry, buffer, minX, maxY, maxZ, minX, maxY, minZ, argb, lineWidth)

        writeLine(entry, buffer, minX, minY, minZ, minX, maxY, minZ, argb, lineWidth)
        writeLine(entry, buffer, maxX, minY, minZ, maxX, maxY, minZ, argb, lineWidth)
        writeLine(entry, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, argb, lineWidth)
        writeLine(entry, buffer, minX, minY, maxZ, minX, maxY, maxZ, argb, lineWidth)
    }

    private fun writeLine(
        entry: MatrixStack.Entry,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        argb: Int,
        lineWidth: Float
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        val lenSq = dx * dx + dy * dy + dz * dz
        val invLen = if (lenSq > 1.0E-12) 1.0 / sqrt(lenSq) else 0.0
        val nx = (dx * invLen).toFloat()
        val ny = (dy * invLen).toFloat()
        val nz = (dz * invLen).toFloat()

        buffer.vertex(entry, x1.toFloat(), y1.toFloat(), z1.toFloat()).color(argb).normal(entry, nx, ny, nz).lineWidth(lineWidth)
        buffer.vertex(entry, x2.toFloat(), y2.toFloat(), z2.toFloat()).color(argb).normal(entry, nx, ny, nz).lineWidth(lineWidth)
    }

    private fun renderTextBatch(matrices: MatrixStack, count: Int) {
        val frustum = FrustumHolder.currentFrustum

        for (idx in 0 until count) {
            val i = idx * 3
            val x = textData[i]
            val y = textData[i + 1]
            val z = textData[i + 2]

            if (!FrustumUtils.isVisible(frustum, x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5)) continue

            val scale = textScales[idx]
            val width = textWidths[idx]
            val flags = textFlags[idx]
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
                FULL_BRIGHT
            )

            matrices.pop()
        }
    }

    @JvmStatic
    fun drawFilledBox(pos: Vec3d, color: Color, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        addBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, color, true, depth, 10f)
    }

    @JvmStatic
    fun drawFilledBox(box: Box, color: Color, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        addBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, true, depth, 10f)
    }

    @JvmStatic
    fun drawWireFrameBox(pos: Vec3d, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        addBox(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, color, false, depth, thickness)
    }

    @JvmStatic
    fun drawWireFrameBox(box: Box, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        if (boxCount >= MAX_BOXES) return
        addBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, false, depth, thickness)
    }

    @JvmStatic
    fun drawBox(box: Box, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        if (boxCount < MAX_BOXES) addBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, true, depth, 10f)
        if (boxCount < MAX_BOXES) addBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, false, depth, thickness)
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
        val yawRad = Math.toRadians(camera.yaw.toDouble())
        val pitchRad = Math.toRadians(camera.pitch.toDouble())
        val cosPitch = kotlin.math.cos(pitchRad)
        val startX = camera.cameraPos.x + (-kotlin.math.sin(yawRad) * cosPitch) * 0.1
        val startY = camera.cameraPos.y + (-kotlin.math.sin(pitchRad)) * 0.1
        val startZ = camera.cameraPos.z + (kotlin.math.cos(yawRad) * cosPitch) * 0.1
        addLine(startX, startY, startZ, targetPos.x, targetPos.y, targetPos.z, color.packed, thickness, depth, true)
    }

    @JvmStatic
    fun drawText(text: String, pos: Vec3d, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        if (textCount >= MAX_TEXTS) return
        val i = textCount * 3

        textData[i] = pos.x
        textData[i + 1] = pos.y
        textData[i + 2] = pos.z

        textStrings[textCount] = text
        textScales[textCount] = scale
        textWidths[textCount] = client.textRenderer.getWidth(text).toFloat()
        textFlags[textCount] = (if (seeThrough) 1 else 0) or
                (if (backgroundBox) 2 else 0) or
                (if (increase) 4 else 0) or
                (if (translate) 8 else 0)

        textCount++
    }

    private fun addBox(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
                       color: Color, filled: Boolean, depth: Boolean, thickness: Float) {
        if (!FrustumUtils.isVisible(FrustumHolder.currentFrustum, x1, y1, z1, x2, y2, z2)) return

        val i = boxCount * 6
        boxData[i] = x1; boxData[i + 1] = y1; boxData[i + 2] = z1
        boxData[i + 3] = x2; boxData[i + 4] = y2; boxData[i + 5] = z2

        boxColors[boxCount] = color.packed

        boxFlags[boxCount] = (if (filled) 1 else 0) or
                (if (depth) 2 else 0) or
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
