package com.v5.swift.util.render

import com.v5.swift.mixins.FrustumInvoker
import net.minecraft.client.render.Frustum
import net.minecraft.util.math.Box
import org.joml.FrustumIntersection

object FrustumUtils {

  fun isVisible(frustum: Frustum, box: Box): Boolean {
    return isVisible(frustum, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
  }

  fun isVisible(
    frustum: Frustum,
    minX: Double,
    minY: Double,
    minZ: Double,
    maxX: Double,
    maxY: Double,
    maxZ: Double,
  ): Boolean {
    val result = (frustum as FrustumInvoker).invokeIntersectAab(minX, minY, minZ, maxX, maxY, maxZ)
    return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT
  }

}
