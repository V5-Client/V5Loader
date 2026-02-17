package dev.quiteboring.swift.finder.movement

import dev.quiteboring.swift.finder.costs.ActionCosts
import dev.quiteboring.swift.finder.helper.BlockStateAccessor
import dev.quiteboring.swift.finder.precompute.PrecomputedData
import dev.quiteboring.swift.finder.precompute.SafePositionCache
import dev.quiteboring.swift.finder.precompute.WallDistanceCalculator
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient

class CalculationContext {

  val mc: MinecraftClient = MinecraftClient.getInstance()
  val world = mc.world!!

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
