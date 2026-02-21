package com.v5.swift.finder.movement.movements

import com.v5.swift.finder.movement.CalculationContext
import com.v5.swift.finder.movement.MovementResult
import com.v5.swift.finder.precompute.PrecomputedData

object MovementFly {

  private const val DIST_1 = 1.0
  private const val DIST_SQRT2 = 1.4142135623730951
  private const val DIST_SQRT3 = 1.7320508075688772

  private const val MIN_VERTICAL_CLEARANCE = 2
  private const val VERTICAL_OBSTACLE_PENALTY = 5.0

  private const val WALL_CHECK_DIST = 3
  private const val WALL_PENALTY_TOUCHING = 3.5
  private const val WALL_PENALTY_CLOSE = 1.8
  private const val WALL_PENALTY_NEAR = 0.6

  private const val PURE_VERTICAL_BASE = 1.2
  private const val DIAGONAL_VERTICAL_BASE = 0.2

  private const val ASCEND_EARLY = 0.0
  private const val ASCEND_EARLY_MID = 0.8
  private const val ASCEND_MID = 3.5
  private const val ASCEND_LATE = 7.0

  private const val DESCEND_EARLY = 7.0
  private const val DESCEND_EARLY_MID = 4.0
  private const val DESCEND_MID = 2.0
  private const val DESCEND_LATE = 0.0

  private const val LEVEL_CRUISE_BONUS = 0.3

  private const val BELOW_CRUISE_EARLY_PENALTY = 0.5
  private const val ALTITUDE_DEVIATION_MID_PENALTY = 0.15

  private val BASE_DISTANCES = doubleArrayOf(0.0, DIST_1, DIST_SQRT2, DIST_SQRT3)

  @JvmStatic
  fun calculate(
      ctx: CalculationContext,
      x: Int, y: Int, z: Int,
      dx: Int, dy: Int, dz: Int,
      res: MovementResult
  ) {
    val destX = x + dx
    val destY = y + dy
    val destZ = z + dz
    val pre = ctx.precomputedData

    if (!pre.isPassableForFlying(destX, destY, destZ)) return
    if (!pre.isPassableForFlying(destX, destY + 1, destZ)) return
    if (dy > 0 && !pre.isPassableForFlying(x, y + 2, z)) return

    val isDiagonalHorizontal = dx != 0 && dz != 0
    if (isDiagonalHorizontal) {
      if (!pre.isPassableForFlying(x + dx, destY, z)) return
      if (!pre.isPassableForFlying(x, destY, z + dz)) return
      if (!pre.isPassableForFlying(x + dx, destY + 1, z)) return
      if (!pre.isPassableForFlying(x, destY + 1, z + dz)) return

      if (dy != 0) {
        if (!pre.isPassableForFlying(x + dx, y + dy, z)) return
        if (!pre.isPassableForFlying(x, y + dy, z + dz)) return
      }
    }

    res.set(destX, destY, destZ)

    val axisCount = (if (dx != 0) 1 else 0) + (if (dy != 0) 1 else 0) + (if (dz != 0) 1 else 0)
    var cost = BASE_DISTANCES[axisCount] * ctx.cost.FLY_ONE_BLOCK_TIME

    val dxStart = x - ctx.startX
    val dzStart = z - ctx.startZ
    val dxGoal = x - ctx.goalX
    val dzGoal = z - ctx.goalZ
    val distFromStartSq = dxStart * dxStart + dzStart * dzStart
    val distToGoalSq = dxGoal * dxGoal + dzGoal * dzGoal
    val totalSq = distFromStartSq + distToGoalSq
    val progress = if (totalSq > 0) distFromStartSq.toDouble() / totalSq else 0.5

    if (dy != 0) {
      cost += if (isDiagonalHorizontal || dx != 0 || dz != 0) DIAGONAL_VERTICAL_BASE else PURE_VERTICAL_BASE

      cost += if (dy > 0) {
        if (destY > ctx.cruiseY + 2) {
          ASCEND_LATE + (destY - ctx.cruiseY - 2) * 1.5
        } else when {
          progress < 0.25 -> ASCEND_EARLY
          progress < 0.40 -> ASCEND_EARLY_MID
          progress < 0.70 -> ASCEND_MID
          else -> ASCEND_LATE
        }
      } else {
        if (progress > 0.7 && destY < ctx.goalY - 1) {
          DESCEND_MID + (ctx.goalY - 1 - destY) * 1.0
        } else when {
          progress < 0.25 -> DESCEND_EARLY
          progress < 0.45 -> DESCEND_EARLY_MID
          progress < 0.70 -> DESCEND_MID
          else -> DESCEND_LATE
        }
      }
    } else if (progress > 0.2 && progress < 0.8) {
      cost -= LEVEL_CRUISE_BONUS
    }

    cost += when {
      progress < 0.3 -> {
        if (destY < ctx.cruiseY) (ctx.cruiseY - destY) * BELOW_CRUISE_EARLY_PENALTY else 0.0
      }
      progress < 0.7 -> {
        val deviation = if (destY > ctx.cruiseY) destY - ctx.cruiseY else ctx.cruiseY - destY
        deviation * ALTITUDE_DEVIATION_MID_PENALTY
      }
      else -> when {
        destY > ctx.cruiseY + 2 -> (destY - ctx.cruiseY - 2) * 0.2
        destY < ctx.goalY - 2 -> (ctx.goalY - 2 - destY) * 0.3
        else -> 0.0
      }
    }

    var vertCost = 0.0
    if (!pre.isPassableForFlying(destX, destY - 1, destZ)) {
      vertCost = VERTICAL_OBSTACLE_PENALTY * MIN_VERTICAL_CLEARANCE
    } else if (!pre.isPassableForFlying(destX, destY - 2, destZ)) {
      vertCost = VERTICAL_OBSTACLE_PENALTY * (MIN_VERTICAL_CLEARANCE - 1)
    }
    cost += vertCost

    if (progress <= 0.88) {
      cost += getHorizontalClearanceCost(pre, destX, destY, destZ, progress)
    }

    res.cost = cost
  }

  @JvmStatic
  private fun getHorizontalClearanceCost(
      pre: PrecomputedData,
      x: Int, y: Int, z: Int,
      progress: Double
  ): Double {
    val scale = when {
      progress > 0.75 -> 0.3
      progress > 0.65 -> 0.6
      else -> 1.0
    }

    var minClearance = WALL_CHECK_DIST

    for (d in 1..WALL_CHECK_DIST) {
      if (!pre.isPassableForFlying(x, y, z - d) || !pre.isPassableForFlying(x, y + 1, z - d)) {
        val clearance = d - 1
        if (clearance < minClearance) minClearance = clearance
        break
      }
    }
    if (minClearance == 0) return WALL_PENALTY_TOUCHING * scale

    for (d in 1..WALL_CHECK_DIST) {
      if (!pre.isPassableForFlying(x, y, z + d) || !pre.isPassableForFlying(x, y + 1, z + d)) {
        val clearance = d - 1
        if (clearance < minClearance) minClearance = clearance
        break
      }
    }
    if (minClearance == 0) return WALL_PENALTY_TOUCHING * scale

    for (d in 1..WALL_CHECK_DIST) {
      if (!pre.isPassableForFlying(x + d, y, z) || !pre.isPassableForFlying(x + d, y + 1, z)) {
        val clearance = d - 1
        if (clearance < minClearance) minClearance = clearance
        break
      }
    }
    if (minClearance == 0) return WALL_PENALTY_TOUCHING * scale

    for (d in 1..WALL_CHECK_DIST) {
      if (!pre.isPassableForFlying(x - d, y, z) || !pre.isPassableForFlying(x - d, y + 1, z)) {
        val clearance = d - 1
        if (clearance < minClearance) minClearance = clearance
        break
      }
    }
    if (minClearance == 0) return WALL_PENALTY_TOUCHING * scale

    if (minClearance > 0) {
      if (!pre.isPassableForFlying(x + 1, y, z + 1) || !pre.isPassableForFlying(x + 1, y + 1, z + 1)) minClearance = 0
      else if (!pre.isPassableForFlying(x + 1, y, z - 1) || !pre.isPassableForFlying(x + 1, y + 1, z - 1)) minClearance = 0
      else if (!pre.isPassableForFlying(x - 1, y, z + 1) || !pre.isPassableForFlying(x - 1, y + 1, z + 1)) minClearance = 0
      else if (!pre.isPassableForFlying(x - 1, y, z - 1) || !pre.isPassableForFlying(x - 1, y + 1, z - 1)) minClearance = 0
    }

    val basePenalty = when (minClearance) {
      0 -> WALL_PENALTY_TOUCHING
      1 -> WALL_PENALTY_CLOSE
      2 -> WALL_PENALTY_NEAR
      else -> 0.0
    }

    return basePenalty * scale
  }
}
