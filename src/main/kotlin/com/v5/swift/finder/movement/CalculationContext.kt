package com.v5.swift.finder.movement

import com.v5.swift.finder.costs.ActionCosts
import com.v5.swift.finder.helper.BlockStateAccessor
import com.v5.swift.finder.precompute.PrecomputedData
import com.v5.swift.finder.precompute.SafePositionCache
import com.v5.swift.finder.precompute.WallDistanceCalculator
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import kotlin.math.abs
import kotlin.math.max

class CalculationContext {

  val mc: MinecraftClient = MinecraftClient.getInstance()
  val world = requireNotNull(mc.world) { "World is not loaded" }

  val bsa = BlockStateAccessor(world)
  val cost = ActionCosts()
  val maxFallHeight = 20
  val flyMinY = world.bottomY
  val flyMaxY = world.topYInclusive - 1

  val precomputedData = PrecomputedData(bsa)
  val safeCache = SafePositionCache(this)
  val wdc = WallDistanceCalculator(this)

  @JvmField var startX: Int = 0
  @JvmField var startY: Int = 0
  @JvmField var startZ: Int = 0

  @JvmField var goalX: Int = 0
  @JvmField var goalY: Int = 0
  @JvmField var goalZ: Int = 0

  @JvmField var cruiseY: Int = 0

  @Volatile
  private var transientAvoidZones: Array<AvoidZone> = emptyArray()

  data class AvoidZone(
    val x: Int,
    val y: Int,
    val z: Int,
    val radiusSq: Int,
    val penalty: Double,
    val maxYDiff: Int = 2
  )

  fun getFluidPenalty(x: Int, y: Int, z: Int): Double {
    var penalty = 0.0
    if (precomputedData.isFluid(x, y, z, get(x, y, z))) penalty += 20.0
    if (precomputedData.isFluid(x, y + 1, z, get(x, y + 1, z))) penalty += 20.0
    return penalty
  }

  fun setTransientAvoidZones(zones: Array<AvoidZone>) {
    transientAvoidZones = zones
  }

  fun getTransientAvoidPenalty(x: Int, y: Int, z: Int): Double {
    val zones = transientAvoidZones
    if (zones.isEmpty()) return 0.0

    var penalty = 0.0
    for (zone in zones) {
      if (abs(y - zone.y) > zone.maxYDiff) continue

      val dx = x - zone.x
      val dz = z - zone.z
      val distSq = dx.toLong() * dx.toLong() + dz.toLong() * dz.toLong()
      if (distSq > zone.radiusSq.toLong()) continue

      val normalized = if (zone.radiusSq <= 1) 0.0 else distSq.toDouble() / zone.radiusSq.toDouble()
      val falloff = max(0.2, 1.0 - normalized)
      penalty += zone.penalty * falloff
    }

    return penalty
  }

  fun setFlightParameters(sx: Int, sy: Int, sz: Int, gx: Int, gy: Int, gz: Int) {
    startX = sx
    startY = sy
    startZ = sz
    goalX = gx
    goalY = gy
    goalZ = gz
    cruiseY = maxOf(sy, gy) + CRUISE_BUFFER
  }

  fun get(x: Int, y: Int, z: Int): BlockState =
    bsa.get(x, y, z)

  companion object {
    const val CRUISE_BUFFER = 6
  }

}
