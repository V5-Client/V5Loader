package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.systems.RenderSystem
import com.chattriggers.ctjs.internal.engine.CTEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.ColorHelper
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf

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
        matrices.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z)
        renderBoxes(matrices, pendingBoxes)
        renderLines(matrices, pendingLines)
        matrices.pop()
        renderTexts(matrices, pendingTexts, camera.pos, camera.rotation)

        bufferSource.draw()
        RenderSystem.lineWidth(1f)
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
            key.thickness?.let(RenderSystem::lineWidth)
            val buffer = bufferSource.getBuffer(key.layer)

            for ((box, color, filled) in batch) {
                if (filled) {
                    VertexRendering.drawFilledBox(
                        matrices,
                        buffer,
                        box.minX.toFloat(), box.minY.toFloat(), box.minZ.toFloat(),
                        box.maxX.toFloat(), box.maxY.toFloat(), box.maxZ.toFloat(),
                        color.rf, color.gf, color.bf, color.af,
                    )
                } else {
                    VertexRendering.drawBox(
                        matrices.peek(),
                        buffer,
                        box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ,
                        color.rf, color.gf, color.bf, color.af,
                    )
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
            RenderSystem.lineWidth(requireNotNull(key.thickness))
            val buffer = bufferSource.getBuffer(key.layer)
            for ((start, end, color) in batch) {
                writeLine(matrices.peek(), buffer, start, end, color.packed)
            }
            bufferSource.draw()
        }
    }

    private fun writeLine(entry: MatrixStack.Entry, buffer: VertexConsumer, start: Vec3d, end: Vec3d, argb: Int) {
        val normal = end.subtract(start).normalize()

        buffer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat()).color(argb).normal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
        buffer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat()).color(argb).normal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
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
        val start = camera.pos.add(Vec3d.fromPolar(camera.pitch, camera.yaw).multiply(0.1))
        drawLine(start, targetPos, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawText(text: String, pos: Vec3d, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        texts += TextCommand(text, pos, scale, backgroundBox, increase, seeThrough, translate)
    }
}
