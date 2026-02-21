package com.v5.swift.finder.costs

class ActionCosts {

  val INF_COST = 1e6

  // For fly pathfinding
  @JvmField val FLY_ONE_BLOCK_TIME = 1.0 / 0.7

  // For walk pathfinding
  @JvmField val SPRINT_ONE_BLOCK_TIME = 1.0 / 0.2806
  @JvmField val SPRINT_DIAGONAL_TIME = SPRINT_ONE_BLOCK_TIME * 1.4142135623730951
  @JvmField val MOMENTUM_LOSS_PENALTY = 6.0
  @JvmField val JUMP_PENALTY = 2.0
  @JvmField val GAP_JUMP_REWARD_OFFSET = 1.5
  @JvmField val SLAB_ASCENT_TIME = SPRINT_ONE_BLOCK_TIME * 1.1
  @JvmField val WALK_OFF_EDGE_TIME = SPRINT_ONE_BLOCK_TIME * 0.5
  @JvmField val LAND_RECOVERY_TIME = 2.0
  @JvmField val JUMP_UP_ONE_BLOCK_TIME: Double = 18.0 + MOMENTUM_LOSS_PENALTY + SPRINT_ONE_BLOCK_TIME

  private val fallTimes: DoubleArray = generateFallTimes()

  fun getFallTime(blocks: Int): Double {
    if (blocks <= 0) return 0.0
    if (blocks >= fallTimes.size) return INF_COST
    return fallTimes[blocks] + LAND_RECOVERY_TIME
  }

  private fun generateFallTimes(): DoubleArray {
    val times = DoubleArray(257)
    var currentDistance = 0.0
    var tick = 0
    var velocity = 0.0

    for (targetDistance in 1..256) {
      while (currentDistance < targetDistance) {
        velocity = (velocity - 0.08) * 0.98
        currentDistance -= velocity
        tick++
      }
      times[targetDistance] = tick.toDouble()
    }

    return times
  }

}
