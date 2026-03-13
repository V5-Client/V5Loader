package com.v5.swift.finder.goal

import com.v5.swift.finder.movement.CalculationContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Goal(
  val startX: Int,
  val startZ: Int,
  val goalX: Int,
  val goalY: Int,
  val goalZ: Int,
  ctx: CalculationContext
) : IGoal {

  // precompute cuz it's slightly faster ig.
  private val sprintCost = ctx.cost.SPRINT_ONE_BLOCK_TIME
  private val diagonalCost = ctx.cost.SPRINT_DIAGONAL_TIME
  private val fallCostPerBlock = ctx.cost.getFallTime(2) * 0.5
  private val jumpCostPerBlock = ctx.cost.JUMP_UP_ONE_BLOCK_TIME
  private val verticalReluctance = sprintCost * 0.35

  override fun isAtGoal(x: Int, y: Int, z: Int): Boolean {
    return x == goalX && y == goalY && z == goalZ
  }

  override fun heuristic(x: Int, y: Int, z: Int): Double {
    val dx = abs(x.toLong() - goalX.toLong())
    val dz = abs(z.toLong() - goalZ.toLong())
    val dy = y.toLong() - goalY.toLong()

    val minHoriz = min(dx, dz)
    val maxHoriz = max(dx, dz)

    val horizontal = minHoriz.toDouble() * diagonalCost + (maxHoriz - minHoriz).toDouble() * sprintCost

    if (dy == 0L) {
      return horizontal
    }

    val absDy = abs(dy).toDouble()

    return horizontal + if (dy > 0L) {
      dy.toDouble() * fallCostPerBlock
    } else {
      absDy * jumpCostPerBlock
    } + absDy * verticalReluctance
  }

}
