package com.v5.swift.finder.goal

import com.v5.swift.finder.movement.CalculationContext
import kotlin.math.abs
import kotlin.math.hypot

class GoalFly(
    val startX: Int,
    val startY: Int,
    val startZ: Int,
    val goalX: Int,
    val goalY: Int,
    val goalZ: Int,
    val ctx: CalculationContext,
) : IGoal {

  private val flyCost = ctx.cost.FLY_ONE_BLOCK_TIME
  private val cruiseY = maxOf(startY, goalY) + CalculationContext.Companion.CRUISE_BUFFER

  private val startToGoalDx = startX - goalX
  private val startToGoalDz = startZ - goalZ

  companion object {
    private const val CROSS_PRODUCT_WEIGHT = 0.001
  }

  override fun isAtGoal(x: Int, y: Int, z: Int): Boolean {
    return x == goalX && y == goalY && z == goalZ
  }

  override fun heuristic(x: Int, y: Int, z: Int): Double {
    val dx = (x - goalX).toDouble()
    val dz = (z - goalZ).toDouble()

    val horizontalDist = hypot(dx, dz)
    val verticalCost = estimateVerticalCost(x, z, y)
    var h = (horizontalDist * flyCost) + verticalCost
    val crossProduct = abs(dx * startToGoalDz.toDouble() - dz * startToGoalDx.toDouble())
    h += crossProduct * CROSS_PRODUCT_WEIGHT

    return h
  }

  private fun estimateVerticalCost(x: Int, z: Int, y: Int): Double {
    val progress = calculateProgress(x, z)

    return when {
      progress < 0.3 -> {
        val toCruise = if (y < cruiseY) (cruiseY - y) * 0.5 else 0.0
        val cruiseToGoal = if (cruiseY > goalY) (cruiseY - goalY) * 0.3 else 0.0
        toCruise + cruiseToGoal
      }
      progress < 0.7 -> {
        val deviation = abs(y - cruiseY) * 0.3
        val toGoal = if (cruiseY > goalY) (cruiseY - goalY) * 0.2 else abs(y - goalY) * 0.3
        deviation + toGoal
      }
      else -> {
        abs(y - goalY) * 0.4
      }
    }
  }

  private fun calculateProgress(x: Int, z: Int): Double {
    val dxStart = x - startX
    val dzStart = z - startZ
    val dxGoal = x - goalX
    val dzGoal = z - goalZ

    val distFromStartSq = dxStart.toLong() * dxStart.toLong() + dzStart.toLong() * dzStart.toLong()
    val distToGoalSq = dxGoal.toLong() * dxGoal.toLong() + dzGoal.toLong() * dzGoal.toLong()
    val totalSq = distFromStartSq + distToGoalSq

    return if (totalSq > 0) distFromStartSq.toDouble() / totalSq else 0.5
  }

}
