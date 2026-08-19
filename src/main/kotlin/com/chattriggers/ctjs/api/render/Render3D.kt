package com.chattriggers.ctjs.api.render
import com.chattriggers.ctjs.api.client.MinecraftCompat

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
    fun drawWireFrameBox(pos: Vec3, color: Color, thickness: Float = 5f, depth: Boolean = false) =
        drawWireFrameBox(AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1), color, thickness, depth)

    @JvmStatic
    @JvmOverloads
    fun drawWireFrameBox(box: AABB, color: Color, thickness: Float = 5f, depth: Boolean = false) {
        Gizmos.cuboid(box, GizmoStyle.stroke(color.packed, thickness)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawBox(box: AABB, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color.packed, thickness, color.packed)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawStyledBox(pos: Vec3, color1: Color, color2: Color, wireThickness: Float = 5f, depth: Boolean = false) {
        val box = AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1)
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color2.packed, wireThickness, color1.packed)).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawSizedBox(pos: Vec3, width: Double, height: Double, length: Double, color: Color, filled: Boolean = true, thickness: Float = 1f, depth: Boolean = false) {
        val box = AABB(pos.x - width / 2, pos.y, pos.z - length / 2, pos.x + width / 2, pos.y + height, pos.z + length / 2)
        Gizmos.cuboid(box, if (filled) GizmoStyle.fill(color.packed) else GizmoStyle.stroke(color.packed, thickness)).depth(depth)
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
    fun drawLine(start: Vec3, end: Vec3, color: Color, thickness: Float = 3f, depth: Boolean = false) {
        Gizmos.line(start, end, color.packed, thickness).depth(depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawTracer(targetPos: Vec3, color: Color, thickness: Float = 2f, depth: Boolean = false) {
        val camera = MinecraftCompat.mainCamera(client.gameRenderer)
        val start = camera.position().add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.1))
        drawLine(start, targetPos, color, thickness, depth)
    }

    @JvmStatic
    @JvmOverloads
    fun drawText(text: String, pos: Vec3, scale: Float = 1f, backgroundBox: Boolean = false, increase: Boolean = false, seeThrough: Boolean = false, translate: Boolean = true) {
        val camera = MinecraftCompat.mainCamera(client.gameRenderer)
        val distanceScale = if (increase) (pos.distanceTo(camera.position()).toFloat() / 120f).coerceAtLeast(0.01f) else 1f
        val style = TextGizmo.Style.whiteAndCentered().withScale(TextGizmo.Style.DEFAULT_SCALE * scale * distanceScale)
        Gizmos.billboardText(text, if (translate) pos else camera.position(), style).apply {
            if (seeThrough) setAlwaysOnTop()
        }
    }

    private fun net.minecraft.gizmos.GizmoProperties.depth(depth: Boolean) = apply {
        if (!depth) setAlwaysOnTop()
    }
}
