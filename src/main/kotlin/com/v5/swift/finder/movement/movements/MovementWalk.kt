package com.v5.swift.finder.movement.movements

import com.v5.swift.finder.movement.CalculationContext
import com.v5.swift.finder.movement.MovementResult
import com.v5.swift.finder.precompute.PrecomputedData
import net.minecraft.block.*
import kotlin.math.abs

object MovementWalk {

  @JvmStatic
  fun traverse(
    ctx: CalculationContext,
    x: Int, y: Int, z: Int,
    destX: Int, destZ: Int,
    res: MovementResult
  ) {
    if (!ctx.safeCache.isSafe(destX, y, destZ)) return

    res.set(destX, y, destZ)
    res.cost = ctx.cost.SPRINT_ONE_BLOCK_TIME + ctx.wdc.getPathPenalty(destX, y, destZ) + ctx.getFluidPenalty(destX, y, destZ)
  }

  @JvmStatic
  fun diagonal(
    ctx: CalculationContext,
    x: Int, y: Int, z: Int,
    destX: Int, destZ: Int,
    res: MovementResult
  ) {
    if (!ctx.safeCache.isSafe(destX, y, destZ)) return

    val pre = ctx.precomputedData
    val bsa = ctx.bsa

    val stateXFeet = bsa.get(destX, y, z)
    if (pre.isSolid(destX, y, z, stateXFeet)) return

    val stateZFeet = bsa.get(x, y, destZ)
    if (pre.isSolid(x, y, destZ, stateZFeet)) return

    val stateXHead = bsa.get(destX, y + 1, z)
    if (pre.isSolid(destX, y + 1, z, stateXHead)) return

    val stateZHead = bsa.get(x, y + 1, destZ)
    if (pre.isSolid(x, y + 1, destZ, stateZHead)) return

    res.set(destX, y, destZ)
    res.cost = ctx.cost.SPRINT_DIAGONAL_TIME + ctx.wdc.getPathPenalty(destX, y, destZ) + ctx.getFluidPenalty(destX, y, destZ)
  }

  @JvmStatic
  fun ascend(
    ctx: CalculationContext,
    x: Int, y: Int, z: Int,
    destX: Int, destZ: Int,
    res: MovementResult
  ) {
    val precomputed = ctx.precomputedData
    val bsa = ctx.bsa

    val jumpHeadState = bsa.get(x, y + 2, z)
    if (!precomputed.isPassable(x, y + 2, z, jumpHeadState)) return

    val groundState = bsa.get(destX, y, destZ)

    val groundBlock = groundState.block
    if (groundBlock is FenceBlock || groundBlock is FenceGateBlock || groundBlock is WallBlock) return

    if (!precomputed.isSolid(destX, y, destZ, groundState)) return

    val feetState = bsa.get(destX, y + 1, destZ)
    if (!precomputed.isPassable(destX, y + 1, destZ, feetState)) return

    val destHeadState = bsa.get(destX, y + 2, destZ)
    if (!precomputed.isPassable(destX, y + 2, destZ, destHeadState)) return

    val srcGroundState = bsa.get(x, y - 1, z)
    val srcIsBottomSlab = precomputed.isBottomSlab(x, y - 1, z, srcGroundState)
    val destIsBottomSlab = precomputed.isBottomSlab(destX, y, destZ, groundState)

    if (srcIsBottomSlab && !destIsBottomSlab) {
      return
    }

    res.set(destX, y + 1, destZ)

    val srcBlock = srcGroundState.block
    val baseCost = when {
      destIsBottomSlab -> ctx.cost.SLAB_ASCENT_TIME
      srcBlock is StairsBlock -> ctx.cost.SLAB_ASCENT_TIME
      groundBlock is StairsBlock -> ctx.cost.SLAB_ASCENT_TIME
      else -> ctx.cost.JUMP_UP_ONE_BLOCK_TIME
    }

    res.cost = baseCost + ctx.wdc.getPathPenalty(destX, y + 1, destZ) + ctx.getFluidPenalty(destX, y + 1, destZ)
  }

  @JvmStatic
  fun descend(
    ctx: CalculationContext,
    x: Int, y: Int, z: Int,
    destX: Int, destZ: Int,
    res: MovementResult
  ) {
    val precomputed = ctx.precomputedData
    val bsa = ctx.bsa

    val stateY1 = bsa.get(destX, y + 1, destZ)
    if (!precomputed.isPassable(destX, y + 1, destZ, stateY1)) return

    val stateY = bsa.get(destX, y, destZ)
    if (!precomputed.isPassable(destX, y, destZ, stateY)) return

    val maxFall = ctx.maxFallHeight
    val cost = ctx.cost

    for (fallDist in 1..maxFall) {
      val checkY = y - fallDist
      val state = bsa.get(destX, checkY, destZ)
      val data = precomputed.getData(destX, checkY, destZ, state)

      if ((data and PrecomputedData.Companion.PASSABLE) != 0) continue
      if ((data and PrecomputedData.Companion.SOLID) == 0) return

      val destY = checkY + 1
      val destFeetState = bsa.get(destX, destY, destZ)
      if (!precomputed.isPassable(destX, destY, destZ, destFeetState)) return

      val destHeadState = bsa.get(destX, destY + 1, destZ)
      if (!precomputed.isPassable(destX, destY + 1, destZ, destHeadState)) return

      res.set(destX, destY, destZ)

      var totalCost = cost.WALK_OFF_EDGE_TIME + cost.getFallTime(fallDist)
      if (fallDist > 3) {
        val excess = fallDist - 3
        totalCost += excess * excess * 2.0
      }
      totalCost += ctx.wdc.getPathPenalty(destX, destY, destZ) + ctx.getFluidPenalty(destX, destY, destZ)

      res.cost = totalCost
      return
    }
  }

  @JvmStatic
  fun jumpGap(
      ctx: CalculationContext,
      x: Int, y: Int, z: Int,
      destX: Int, destZ: Int,
      res: MovementResult
  ) {
    if (!ctx.safeCache.isSafe(destX, y, destZ)) return
    if (!ctx.precomputedData.isPassable(x, y + 2, z)) return

    val dx = destX - x
    val dz = destZ - z
    val dist = if (dx != 0) abs(dx) else abs(dz)

    if (dx != 0 && dz != 0) return

    val dirX = if (dx != 0) dx / dist else 0
    val dirZ = if (dz != 0) dz / dist else 0

    for (i in 1 until dist) {
      val checkX = x + (dirX * i)
      val checkZ = z + (dirZ * i)

      if (!ctx.precomputedData.isPassable(checkX, y, checkZ)) return
      if (!ctx.precomputedData.isPassable(checkX, y + 1, checkZ)) return
      if (!ctx.precomputedData.isPassable(checkX, y + 2, checkZ)) return
    }

    res.set(destX, y, destZ)

    val cost = ctx.cost
    res.cost = cost.JUMP_PENALTY +
      (cost.SPRINT_ONE_BLOCK_TIME * dist) +
      cost.GAP_JUMP_REWARD_OFFSET +
      ctx.wdc.getPathPenalty(destX, y, destZ) +
      ctx.getFluidPenalty(destX, y, destZ)
  }

}
