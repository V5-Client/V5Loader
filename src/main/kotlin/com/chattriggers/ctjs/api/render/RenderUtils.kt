package com.chattriggers.ctjs.api.render

import com.chattriggers.ctjs.internal.engine.CTEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.ColorHelper
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import kotlin.math.max
import kotlin.math.min

object RenderUtils {
    private val client = MinecraftClient.getInstance()
    private val bufferSource = VertexConsumerProvider.immediate(BufferAllocator(2 * 1024 * 1024))
    private val boxes = mutableListOf<BoxCommand>()
    private val lines = mutableListOf<LineCommand>()
    private val texts = mutableListOf<TextCommand>()

    private data class BoxCommand(val box: Box, val color: Color, val filled: Boolean, val depth: Boolean, val thickness: Float)
    private data class LineCommand(val start: Vec3d, val end: Vec3d, val color: Color, val thickness: Float, val depth: Boolean)
    private data class BatchKey(val layer: RenderLayer, val thickness: Float?)
    private data class TextCommand(
        val text: String,
        val pos: Vec3d,
        val scale: Float,
        val backgroundBox: Boolean,
        val increase: Boolean,
        val seeThrough: Boolean,
        val translate: Boolean,
    )

    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val af = a / 255f
        val packed = ColorHelper.getArgb(a, r, g, b)
    }

    init {
        CTEvents.POST_RENDER_WORLD.register { matrices, _ -> render(matrices) }
    }

    private fun render(matrices: MatrixStack) {
        if (boxes.isEmpty() && lines.isEmpty() && texts.isEmpty()) return

        val pendingBoxes = boxes.toList().also { boxes.clear() }
        val pendingLines = lines.toList().also { lines.clear() }
        val pendingTexts = texts.toList().also { texts.clear() }
        val camera = client.gameRenderer.camera

        matrices.push()
        matrices.translate(-camera.cameraPos.x, -camera.cameraPos.y, -camera.cameraPos.z)
        renderBoxes(matrices, pendingBoxes)
        renderLines(matrices, pendingLines)
        matrices.pop()
        renderTexts(matrices, pendingTexts, camera.cameraPos, camera.rotation)

        bufferSource.draw()
    }

    private fun renderBoxes(matrices: MatrixStack, commands: List<BoxCommand>) {
        val batches = commands.groupBy { command ->
            val layer = when {
                command.filled && command.depth -> RenderLayers.TRIANGLE_STRIP
                command.filled -> RenderLayers.TRIANGLE_STRIP_ESP
                command.depth -> RenderLayers.LINE_LIST
                else -> RenderLayers.LINE_LIST_ESP
            }
            BatchKey(layer, command.thickness.takeUnless { command.filled }?.coerceAtLeast(0.1f))
        }

        for ((key, batch) in batches) {
            val buffer = bufferSource.getBuffer(key.layer)

            for ((box, color, filled) in batch) {
                if (filled) {
                    writeFilledBox(matrices.peek(), buffer, box, color.packed)
                } else {
                    writeBox(matrices.peek(), buffer, box, color.packed, key.thickness ?: 1f)
                }
            }
            bufferSource.draw()
        }
    }

    private fun renderLines(matrices: MatrixStack, commands: List<LineCommand>) {
        val batches = commands.groupBy { command ->
            val layer = if (command.depth) RenderLayers.LINE_LIST else RenderLayers.LINE_LIST_ESP
            BatchKey(layer, command.thickness.coerceAtLeast(0.1f))
        }

        for ((key, batch) in batches) {
            val buffer = bufferSource.getBuffer(key.layer)
            for ((start, end, color) in batch) {
                writeLine(matrices.peek(), buffer, start, end, color.packed, requireNotNull(key.thickness))
            }
            bufferSource.draw()
        }
    }

    private fun writeFilledBox(entry: MatrixStack.Entry, buffer: VertexConsumer, box: Box, argb: Int) {
        val minX = min(box.minX, box.maxX).toFloat()
        val minY = min(box.minY, box.maxY).toFloat()
        val minZ = min(box.minZ, box.maxZ).toFloat()
        val maxX = max(box.minX, box.maxX).toFloat()
        val maxY = max(box.minY, box.maxY).toFloat()
        val maxZ = max(box.minZ, box.maxZ).toFloat()

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
        argb: Int,
    ) {
        buffer.vertex(entry, x1, y1, z1).color(argb)
        buffer.vertex(entry, x2, y2, z2).color(argb)
        buffer.vertex(entry, x3, y3, z3).color(argb)
        buffer.vertex(entry, x4, y4, z4).color(argb)
    }

    private fun writeBox(entry: MatrixStack.Entry, buffer: VertexConsumer, box: Box, argb: Int, lineWidth: Float) {
        val min = Vec3d(box.minX, box.minY, box.minZ)
        val max = Vec3d(box.maxX, box.maxY, box.maxZ)
        val corners = arrayOf(
            Vec3d(min.x, min.y, min.z), Vec3d(max.x, min.y, min.z),
            Vec3d(max.x, min.y, max.z), Vec3d(min.x, min.y, max.z),
            Vec3d(min.x, max.y, min.z), Vec3d(max.x, max.y, min.z),
            Vec3d(max.x, max.y, max.z), Vec3d(min.x, max.y, max.z),
        )
        val edges = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        for (i in edges.indices step 2) {
            writeLine(entry, buffer, corners[edges[i]], corners[edges[i + 1]], argb, lineWidth)
        }
    }

    private fun writeLine(entry: MatrixStack.Entry, buffer: VertexConsumer, start: Vec3d, end: Vec3d, argb: Int, lineWidth: Float) {
        val normal = end.subtract(start).normalize()

        buffer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat()).color(argb).normal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).lineWidth(lineWidth)
        buffer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat()).color(argb).normal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).lineWidth(lineWidth)
    }

    private fun renderTexts(matrices: MatrixStack, commands: List<TextCommand>, cameraPos: Vec3d, cameraRotation: Quaternionf) {
        for ((text, pos, scale, backgroundBox, increase, seeThrough, translate) in commands) {
            val relative = pos.subtract(cameraPos)
            matrices.push()
            if (translate) matrices.translate(relative.x, relative.y, relative.z)
            matrices.multiply(cameraRotation)

            val renderScale = if (increase) {
                scale * (relative.length().toFloat() / 120f).coerceAtLeast(0.01f)
            } else {
                scale * 0.025f
            }
            matrices.scale(renderScale, -renderScale, renderScale)

            client.textRenderer.draw(
                text,
                -client.textRenderer.getWidth(text) / 2f,
                0f,
                -1,
                backgroundBox,
                matrices.peek().positionMatrix,
                bufferSource,
                if (seeThrough) TextRenderer.TextLayerType.SEE_THROUGH else TextRenderer.TextLayerType.NORMAL,
                0,
                15728880,
            )
            matrices.pop()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawFilledBox(pos: Vec3d, color: Color, depth: Boolean = false) =
        drawFilledBox(Box(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, depth)

    @JvmStatic
    @JvmOverloads
    fun drawFilledBox(box: Box, color: Color, depth: Boolean = false) {
        boxes += BoxCommand(box, color, true, depth, 1f)
    }

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(pos: Vec3d, color: Color, thickness: Float = 5f, depth: Boolean = false) =
        drawWireFrameBox(Box(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, thickness, depth)

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(box: Box, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        boxes += BoxCommand(box, color, false, depth, thickness)
    }

    @JvmStatic
    @JvmOverloads
    fun drawBox(box: Box, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        drawFilledBox(box, color, depth)
        drawWireFrameBox(box, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawStyledBox(pos: Vec3d, color1: Color, color2: Color, wireThickness: Float = 5f, depth: Boolean = false) {
        val box = Box(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1)
        drawFilledBox(box, color1, depth)
        drawWireFrameBox(box, color2, wireThickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawSizedBox(pos: Vec3d, width: Double, height: Double, length: Double, color: Color, filled: Boolean = true, thickness: Float = 1f, depth: Boolean = false) {
        val box = Box(pos.x - width / 2, pos.y, pos.z - length / 2, pos.x + width / 2, pos.y + height, pos.z + length / 2)
        boxes += BoxCommand(box, color, filled, depth, thickness)
    }

    @JvmStatic
    @JvmOverloads
    fun drawHitbox(entity: Entity, color: Color, thickness: Float = 2f, depth: Boolean = false) =
        drawBox(entity.boundingBox, color, thickness, depth)

    @JvmStatic
    @JvmOverloads
    fun drawLine(start: Vec3d, end: Vec3d, color: Color, thickness: Float = 3f, depth: Boolean = false) {
        lines += LineCommand(start, end, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawTracer(targetPos: Vec3d, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val camera = client.gameRenderer.camera
        val start = camera.cameraPos.add(Vec3d.fromPolar(camera.pitch, camera.yaw).multiply(0.1))
        drawLine(start, targetPos, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawText(text: String, pos: Vec3d, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        texts += TextCommand(text, pos, scale, backgroundBox, increase, seeThrough, translate)
    }
}
