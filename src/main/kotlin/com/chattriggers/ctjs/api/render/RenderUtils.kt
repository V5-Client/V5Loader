package com.chattriggers.ctjs.api.render

import com.chattriggers.ctjs.internal.engine.CTEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.rendertype.RenderType
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.max
import kotlin.math.min

object RenderUtils {
    private val client = Minecraft.getInstance()
    private val bufferSource = MultiBufferSource.immediate(ByteBufferBuilder(2 * 1024 * 1024))
    private val boxes = mutableListOf<BoxCommand>()
    private val lines = mutableListOf<LineCommand>()
    private val texts = mutableListOf<TextCommand>()

    private data class BoxCommand(val box: AABB, val color: Color, val filled: Boolean, val depth: Boolean, val thickness: Float)
    private data class LineCommand(val start: Vec3, val end: Vec3, val color: Color, val thickness: Float, val depth: Boolean)
    private data class BatchKey(val layer: RenderType, val thickness: Float?)
    private data class TextCommand(
        val text: String,
        val pos: Vec3,
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
        val packed = ARGB.color(a, r, g, b)
    }

    init {
        CTEvents.POST_RENDER_WORLD.register { matrices, _ -> render(matrices) }
    }

    private fun render(matrices: PoseStack) {
        if (boxes.isEmpty() && lines.isEmpty() && texts.isEmpty()) return

        val pendingBoxes = boxes.toList().also { boxes.clear() }
        val pendingLines = lines.toList().also { lines.clear() }
        val pendingTexts = texts.toList().also { texts.clear() }
        val camera = client.gameRenderer.mainCamera

        matrices.pushPose()
        matrices.translate(-camera.position().x, -camera.position().y, -camera.position().z)
        renderBoxes(matrices, pendingBoxes)
        renderLines(matrices, pendingLines)
        matrices.popPose()
        renderTexts(matrices, pendingTexts, camera.position(), camera.rotation())

        bufferSource.endBatch()
    }

    private fun renderBoxes(matrices: PoseStack, commands: List<BoxCommand>) {
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
                    writeFilledBox(matrices.last(), buffer, box, color.packed)
                } else {
                    writeBox(matrices.last(), buffer, box, color.packed, key.thickness ?: 1f)
                }
            }
            bufferSource.endBatch()
        }
    }

    private fun renderLines(matrices: PoseStack, commands: List<LineCommand>) {
        val batches = commands.groupBy { command ->
            val layer = if (command.depth) RenderLayers.LINE_LIST else RenderLayers.LINE_LIST_ESP
            BatchKey(layer, command.thickness.coerceAtLeast(0.1f))
        }

        for ((key, batch) in batches) {
            val buffer = bufferSource.getBuffer(key.layer)
            for ((start, end, color) in batch) {
                writeLine(matrices.last(), buffer, start, end, color.packed, requireNotNull(key.thickness))
            }
            bufferSource.endBatch()
        }
    }

    private fun writeFilledBox(entry: PoseStack.Pose, buffer: VertexConsumer, box: AABB, argb: Int) {
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
        entry: PoseStack.Pose,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        argb: Int,
    ) {
        buffer.addVertex(entry, x1, y1, z1).setColor(argb)
        buffer.addVertex(entry, x2, y2, z2).setColor(argb)
        buffer.addVertex(entry, x3, y3, z3).setColor(argb)
        buffer.addVertex(entry, x4, y4, z4).setColor(argb)
    }

    private fun writeBox(entry: PoseStack.Pose, buffer: VertexConsumer, box: AABB, argb: Int, lineWidth: Float) {
        val min = Vec3(box.minX, box.minY, box.minZ)
        val max = Vec3(box.maxX, box.maxY, box.maxZ)
        val corners = arrayOf(
            Vec3(min.x, min.y, min.z), Vec3(max.x, min.y, min.z),
            Vec3(max.x, min.y, max.z), Vec3(min.x, min.y, max.z),
            Vec3(min.x, max.y, min.z), Vec3(max.x, max.y, min.z),
            Vec3(max.x, max.y, max.z), Vec3(min.x, max.y, max.z),
        )
        val edges = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        for (i in edges.indices step 2) {
            writeLine(entry, buffer, corners[edges[i]], corners[edges[i + 1]], argb, lineWidth)
        }
    }

    private fun writeLine(entry: PoseStack.Pose, buffer: VertexConsumer, start: Vec3, end: Vec3, argb: Int, lineWidth: Float) {
        val normal = end.subtract(start).normalize()

        buffer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat()).setColor(argb).setNormal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).setLineWidth(lineWidth)
        buffer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat()).setColor(argb).setNormal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).setLineWidth(lineWidth)
    }

    private fun renderTexts(matrices: PoseStack, commands: List<TextCommand>, cameraPos: Vec3, cameraRotation: Quaternionf) {
        for ((text, pos, scale, backgroundBox, increase, seeThrough, translate) in commands) {
            val relative = pos.subtract(cameraPos)
            matrices.pushPose()
            if (translate) matrices.translate(relative.x, relative.y, relative.z)
            matrices.mulPose(cameraRotation)

            val renderScale = if (increase) {
                scale * (relative.length().toFloat() / 120f).coerceAtLeast(0.01f)
            } else {
                scale * 0.025f
            }
            matrices.scale(renderScale, -renderScale, renderScale)

            client.font.drawInBatch(
                text,
                -client.font.width(text) / 2f,
                0f,
                -1,
                backgroundBox,
                matrices.last().pose(),
                bufferSource,
                if (seeThrough) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL,
                0,
                15728880,
            )
            matrices.popPose()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawFilledBox(pos: Vec3, color: Color, depth: Boolean = false) =
        drawFilledBox(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, depth)

    @JvmStatic
    @JvmOverloads
    fun drawFilledBox(box: AABB, color: Color, depth: Boolean = false) {
        boxes += BoxCommand(box, color, true, depth, 1f)
    }

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(pos: Vec3, color: Color, thickness: Float = 5f, depth: Boolean = false) =
        drawWireFrameBox(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, thickness, depth)

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(box: AABB, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        boxes += BoxCommand(box, color, false, depth, thickness)
    }

    @JvmStatic
    @JvmOverloads
    fun drawBox(box: AABB, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        drawFilledBox(box, color, depth)
        drawWireFrameBox(box, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawStyledBox(pos: Vec3, color1: Color, color2: Color, wireThickness: Float = 5f, depth: Boolean = false) {
        val box = AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1)
        drawFilledBox(box, color1, depth)
        drawWireFrameBox(box, color2, wireThickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawSizedBox(pos: Vec3, width: Double, height: Double, length: Double, color: Color, filled: Boolean = true, thickness: Float = 1f, depth: Boolean = false) {
        val box = AABB(pos.x - width / 2, pos.y, pos.z - length / 2, pos.x + width / 2, pos.y + height, pos.z + length / 2)
        boxes += BoxCommand(box, color, filled, depth, thickness)
    }

    @JvmStatic
    @JvmOverloads
    fun drawHitbox(entity: Entity, color: Color, thickness: Float = 2f, depth: Boolean = false) =
        drawBox(entity.boundingBox, color, thickness, depth)

    @JvmStatic
    @JvmOverloads
    fun drawLine(start: Vec3, end: Vec3, color: Color, thickness: Float = 3f, depth: Boolean = false) {
        lines += LineCommand(start, end, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawTracer(targetPos: Vec3, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val camera = client.gameRenderer.mainCamera
        val start = camera.position().add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.1))
        drawLine(start, targetPos, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawText(text: String, pos: Vec3, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        texts += TextCommand(text, pos, scale, backgroundBox, increase, seeThrough, translate)
    }
}
