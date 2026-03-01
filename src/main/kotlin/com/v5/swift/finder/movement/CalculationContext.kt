package com.v5.swift.finder.movement

import com.v5.swift.finder.costs.ActionCosts
import com.v5.swift.finder.helper.BlockStateAccessor
import com.v5.swift.finder.precompute.PrecomputedData
import com.v5.swift.finder.precompute.SafePositionCache
import com.v5.swift.finder.precompute.WallDistanceCalculator
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient

class CalculationContext {

  val mc: MinecraftClient = MinecraftClient.getInstance()
  val world = requireNotNull(mc.world) { "World is not loaded" }

  val bsa = BlockStateAccessor(world)
  val cost = ActionCosts()
  val maxFallHeight = 20

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

  fun getFluidPenalty(x: Int, y: Int, z: Int): Double {
    var penalty = 0.0
    if (precomputedData.isFluid(x, y, z)) penalty += 20.0
    if (precomputedData.isFluid(x, y + 1, z)) penalty += 20.0
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
