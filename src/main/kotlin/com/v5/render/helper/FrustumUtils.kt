package com.v5.render.helper

import com.v5.mixins.FrustumInvoker
import net.minecraft.client.render.Frustum
import net.minecraft.util.math.Box
import org.joml.FrustumIntersection

object FrustumUtils {
    @JvmStatic
    fun isVisible(frustum: Frustum?, box: Box): Boolean {
        if (frustum == null) return true
        return isVisible(frustum, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
    }

    @JvmStatic
    fun isVisible(
        frustum: Frustum?,
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double
    ): Boolean {
        if (frustum == null) return true
        val plane = (frustum as FrustumInvoker).invokeIntersectAab(minX, minY, minZ, maxX, maxY, maxZ)
        return plane == FrustumIntersection.INSIDE || plane == FrustumIntersection.INTERSECT
    }
}