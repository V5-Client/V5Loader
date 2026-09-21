package com.chattriggers.ctjs.api.render
import com.chattriggers.ctjs.api.client.MinecraftCompat
import com.chattriggers.ctjs.internal.listeners.WorldListener

import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object Render3D {
    private val client = Minecraft.getInstance()

    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        val packed = ARGB.color(a, r, g, b)
    }

    @JvmStatic
    @JvmOverloads
    fun drawFilledBox(pos: Vec3, color: Color, depth: Boolean = false) =
        drawFilledBox(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, depth)

    @JvmStatic
    @JvmOverloads
    fun drawFilledBox(box: AABB, color: Color, depth: Boolean = false) {
        Gizmos.cuboid(box, GizmoStyle.fill(color.packed)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawFilledBoxes(positions: Array<Vec3>, color: Color, depth: Boolean = false) {
        val style = GizmoStyle.fill(color.packed)
        positions.forEach { pos ->
            Gizmos.cuboid(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), style).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawFilledAABBs(boxes: Array<AABB>, color: Color, depth: Boolean = false) {
        val style = GizmoStyle.fill(color.packed)
        boxes.forEach { box -> Gizmos.cuboid(box, style).depth(depth) }
    }

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(pos: Vec3, color: Color, thickness: Float = 5f, depth: Boolean = false) =
        drawWireFrameBox(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, thickness, depth)

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(box: AABB, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        Gizmos.cuboid(box, GizmoStyle.stroke(color.packed, thickness)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBoxes(positions: Array<Vec3>, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        val style = GizmoStyle.stroke(color.packed, thickness)
        positions.forEach { pos ->
            Gizmos.cuboid(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), style).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameAABBs(boxes: Array<AABB>, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        val style = GizmoStyle.stroke(color.packed, thickness)
        boxes.forEach { box -> Gizmos.cuboid(box, style).depth(depth) }
    }

    @JvmStatic
    @JvmOverloads
    fun drawBox(box: AABB, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color.packed, thickness, color.packed)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawBoxes(boxes: Array<AABB>, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val style = GizmoStyle.strokeAndFill(color.packed, thickness, color.packed)
        boxes.forEach { box -> Gizmos.cuboid(box, style).depth(depth) }
    }

    @JvmStatic
    @JvmOverloads
    fun drawStyledBox(pos: Vec3, color1: Color, color2: Color, wireThickness: Float = 5f, depth: Boolean = false) {
        val box = AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1)
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color2.packed, wireThickness, color1.packed)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawStyledBoxes(positions: Array<Vec3>, color1: Color, color2: Color, wireThickness: Float = 5f, depth: Boolean = false) {
        val style = GizmoStyle.strokeAndFill(color2.packed, wireThickness, color1.packed)
        positions.forEach { pos ->
            Gizmos.cuboid(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), style).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawSizedBox(pos: Vec3, width: Double, height: Double, length: Double, color: Color, filled: Boolean = true, thickness: Float = 1f, depth: Boolean = false) {
        val box = AABB(pos.x - width / 2, pos.y, pos.z - length / 2, pos.x + width / 2, pos.y + height, pos.z + length / 2)
        Gizmos.cuboid(box, if (filled) GizmoStyle.fill(color.packed) else GizmoStyle.stroke(color.packed, thickness)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawSizedBoxes(positions: Array<Vec3>, width: Double, height: Double, length: Double, color: Color, filled: Boolean = true, thickness: Float = 1f, depth: Boolean = false) {
        val style = if (filled) GizmoStyle.fill(color.packed) else GizmoStyle.stroke(color.packed, thickness)
        positions.forEach { pos ->
            val box = AABB(pos.x - width / 2, pos.y, pos.z - length / 2, pos.x + width / 2, pos.y + height, pos.z + length / 2)
            Gizmos.cuboid(box, style).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawHitbox(entity: Entity, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val partialTicks = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val box = entity.boundingBox.move(
            entity.xOld + (entity.x - entity.xOld) * partialTicks - entity.x,
            entity.yOld + (entity.y - entity.yOld) * partialTicks - entity.y,
            entity.zOld + (entity.z - entity.zOld) * partialTicks - entity.z,
        )
        drawBox(box, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawHitboxes(entities: Array<Entity>, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val partialTicks = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val style = GizmoStyle.strokeAndFill(color.packed, thickness, color.packed)
        entities.forEach { entity ->
            val box = entity.boundingBox.move(
                entity.xOld + (entity.x - entity.xOld) * partialTicks - entity.x,
                entity.yOld + (entity.y - entity.yOld) * partialTicks - entity.y,
                entity.zOld + (entity.z - entity.zOld) * partialTicks - entity.z,
            )
            Gizmos.cuboid(box, style).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawLine(start: Vec3, end: Vec3, color: Color, thickness: Float = 3f, depth: Boolean = false) {
        Gizmos.line(start, end, color.packed, thickness).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawLines(points: Array<Vec3>, color: Color, thickness: Float = 3f, depth: Boolean = false) {
        for (i in 0 until points.lastIndex) {
            Gizmos.line(points[i], points[i + 1], color.packed, thickness).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawLines(points: Array<Vec3>, colors: Array<Color>, thickness: Float = 3f, depth: Boolean = false) {
        for (i in 0 until minOf(points.lastIndex, colors.lastIndex + 1)) {
            Gizmos.line(points[i], points[i + 1], colors[i].packed, thickness).depth(depth)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawTracer(targetPos: Vec3, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val camera = MinecraftCompat.mainCamera(client.gameRenderer)
        val start = tracerStart(camera.position(), camera.xRot(), camera.yRot())
        drawLine(start, targetPos, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawTracers(targetPositions: Array<Vec3>, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val camera = MinecraftCompat.mainCamera(client.gameRenderer)
        val start = tracerStart(camera.position(), camera.xRot(), camera.yRot())
        targetPositions.forEach { targetPos -> Gizmos.line(start, targetPos, color.packed, thickness).depth(depth) }
    }

    private fun tracerStart(fallbackPosition: Vec3, fallbackXRot: Float, fallbackYRot: Float): Vec3 {
        val position = WorldListener.renderCameraPosition ?: fallbackPosition
        val xRot = if (WorldListener.renderCameraPosition == null) fallbackXRot else WorldListener.renderCameraXRot
        val yRot = if (WorldListener.renderCameraPosition == null) fallbackYRot else WorldListener.renderCameraYRot
        return position.add(Vec3.directionFromRotation(xRot, yRot).scale(0.1))
    }

    @JvmStatic
    @JvmOverloads
    fun drawText(text: String, pos: Vec3, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        val camera = MinecraftCompat.mainCamera(client.gameRenderer)
        val distanceScale = if (increase) (pos.distanceTo(camera.position()).toFloat() / 120f).coerceAtLeast(0.01f) else 1f
        val style = TextGizmo.Style.whiteAndCentered().withScale(TextGizmo.Style.DEFAULT_SCALE * scale * distanceScale)
        drawText(text, if (translate) pos else camera.position(), style, backgroundBox, seeThrough)
    }

    @JvmStatic
    @JvmOverloads
    fun drawTexts(texts: Array<String>, positions: Array<Vec3>, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        val camera = MinecraftCompat.mainCamera(client.gameRenderer)
        for (i in 0 until minOf(texts.size, positions.size)) {
            val pos = positions[i]
            val distanceScale = if (increase) (pos.distanceTo(camera.position()).toFloat() / 120f).coerceAtLeast(0.01f) else 1f
            val style = TextGizmo.Style.whiteAndCentered().withScale(TextGizmo.Style.DEFAULT_SCALE * scale * distanceScale)
            drawText(texts[i], if (translate) pos else camera.position(), style, backgroundBox, seeThrough)
        }
    }

    private fun drawText(text: String, pos: Vec3, style: TextGizmo.Style, backgroundBox: Boolean, seeThrough: Boolean) {
        if (backgroundBox) {
            val pixelScale = style.scale() / 16
            val halfWidth = (client.font.width(text) / 2f + 1) * pixelScale
            val camera = MinecraftCompat.mainCamera(client.gameRenderer)
            val left = Vec3(camera.leftVector()).scale(halfWidth.toDouble())
            val up = Vec3(camera.upVector())
            val top = up.scale(pixelScale.toDouble())
            val bottom = up.scale(-(client.font.lineHeight + 1) * pixelScale.toDouble())
            Gizmos.rect(
                pos.add(left).add(top),
                pos.subtract(left).add(top),
                pos.subtract(left).add(bottom),
                pos.add(left).add(bottom),
                GizmoStyle.fill(client.options.getBackgroundColor(0.25f)),
            ).apply { if (seeThrough) setAlwaysOnTop() }
        }
        Gizmos.billboardText(text, pos, style).apply { if (seeThrough) setAlwaysOnTop() }
    }

    private fun net.minecraft.gizmos.GizmoProperties.depth(depth: Boolean) = apply {
        if (!depth) setAlwaysOnTop()
    }
}
