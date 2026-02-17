package dev.quiteboring.swift.util.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.quiteboring.swift.event.Context
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f

fun Context.drawBox(box: Box, color: Color, esp: Boolean = false) {
  if (!FrustumUtils.isVisible(this.frustum, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
    return
  }

  val matrix = matrixStack ?: return
  val bufferSource = consumers as? VertexConsumerProvider.Immediate ?: return

  val r = color.red / 255f
  val g = color.green / 255f
  val b = color.blue / 255f

  val fillLayer = if (esp) Layers.TRIANGLE_STRIP_ESP else Layers.TRIANGLE_STRIP
  val lineLayer = if (esp) Layers.LINE_LIST_ESP else Layers.LINE_LIST

  matrix.push()
  with(camera.pos) { matrix.translate(-x, -y, -z) }

  VertexRendering.drawFilledBox(
    matrix,
    bufferSource.getBuffer(fillLayer),
    box.minX, box.minY, box.minZ,
    box.maxX, box.maxY, box.maxZ,
    r, g, b, 100 / 255F
  )

  VertexRendering.drawBox(
    matrix.peek(),
    bufferSource.getBuffer(lineLayer),
    box.minX, box.minY, box.minZ,
    box.maxX, box.maxY, box.maxZ,
    r, g, b, 1f
  )

  matrix.pop()
  bufferSource.draw(fillLayer)
  bufferSource.draw(lineLayer)
}

fun Context.drawLine(
  start: Vec3d,
  end: Vec3d,
  color: Color,
  esp: Boolean = false,
  thickness: Float = 2f,
) {
  if (!FrustumUtils.isVisible(
    frustum,
    min(start.x, end.x), min(start.y, end.y), min(start.z, end.z),
    max(start.x, end.x), max(start.y, end.y), max(start.z, end.z)
  )) return

  val matrix = matrixStack ?: return
  val bufferSource = consumers as? VertexConsumerProvider.Immediate ?: return
  val layer = if (esp) Layers.LINE_LIST_ESP else Layers.LINE_LIST
  RenderSystem.lineWidth(thickness)

  matrix.push()
  with(camera.pos) { matrix.translate(-x, -y, -z) }

  val startOffset = Vector3f(start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
  val direction = end.subtract(start)

  VertexRendering.drawVector(
    matrix,
    bufferSource.getBuffer(layer),
    startOffset,
    direction,
    color.rgb
  )

  matrix.pop()
  bufferSource.draw(layer)
}
