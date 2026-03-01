package com.v5.swift.finder.precompute

import com.v5.swift.cache.CachedChunk
import com.v5.swift.cache.CachedWorld
import com.v5.swift.finder.calculate.PathNode
import com.v5.swift.finder.movement.CalculationContext
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import net.minecraft.block.*
import net.minecraft.block.enums.BlockHalf

/**
 * Thank you EpsilonPhoenix for this superb class!
 */
class WallDistanceCalculator(private val ctx: CalculationContext) {

  companion object {
    const val MAX_DIST = 6 // DO NOT LOWER. 6 IS THE PERFECT NUMBER, 4 MAKES SHITTY ZIGZAG PATHS. 5 ISN'T AMAZING.
    private const val OPEN_SPACE_SOFT_CAP = MAX_DIST

    @JvmField val EDGE_PENALTIES = doubleArrayOf(
      24.0,  // edge
      19.5,  // 1 away
      16.0,  // 2 away
      11.5,  // 3 away
      5.5,   // 4 away
      3.7,   // 5 away
      0.5    // 6+ away
    )
    @JvmField val WALL_PENALTIES = doubleArrayOf(
      17.0,  // touching
      13.5,  // 1 away
      11.0,  // 2 away
      6.5,   // 3 away
      3.0,   // 4 away
      1.5,   // 5 away
      0.2    // 6+ away
    )

    private val DX = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private val DZ = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
    private const val CARDINAL_DIR_COUNT = 4

    private const val COMPUTED_FLAG: Byte = 0x40
  }

  private val penaltyCache = Long2DoubleOpenHashMap(16384, 0.6f).apply { defaultReturnValue(Double.NaN) }
  private val allDirMask = (1 shl DX.size) - 1

  fun getPathPenalty(x: Int, y: Int, z: Int): Double {
    val key = PathNode.Companion.coordKey(x, y, z)
    var penalty = penaltyCache.get(key)
    if (!penalty.isNaN()) return penalty

    val chunkX = x shr 4
    val chunkZ = z shr 4
    val chunk = CachedWorld.getChunk(chunkX, chunkZ)

    if (chunk != null) {
      val distData = chunk.getDistanceData(y)
      if (distData != null) {
        val localX = x and 15
        val localZ = z and 15
        val packed = distData[(localZ shl 4) or localX]
        if ((packed.toInt() and COMPUTED_FLAG.toInt()) != 0) {
          val edgeDist = (packed.toInt() and 0x07)
          val wallDist = (packed.toInt() shr 3) and 0x07
          penalty = combinedPenalty(edgeDist, wallDist)
          penaltyCache.put(key, penalty)
          return penalty
        }
      }

      computeChunkDistances(chunk, chunkX, chunkZ, y)

      val newDistData = chunk.getDistanceData(y)
      if (newDistData != null) {
        val localX = x and 15
        val localZ = z and 15
        val packed = newDistData[(localZ shl 4) or localX]
        val edgeDist = (packed.toInt() and 0x07)
        val wallDist = (packed.toInt() shr 3) and 0x07
        penalty = combinedPenalty(edgeDist, wallDist)
        penaltyCache.put(key, penalty)
        return penalty
      }
    }

    penalty = computePenalty(x, y, z)
    penaltyCache.put(key, penalty)
    return penalty
  }

  private fun computeChunkDistances(chunk: CachedChunk, chunkX: Int, chunkZ: Int, y: Int) {
    val data = ByteArray(256)
    val baseX = chunkX shl 4
    val baseZ = chunkZ shl 4

    val edgeFlags = BooleanArray(256)
    val wallFlags = BooleanArray(256)

    for (lz in 0..15) {
      for (lx in 0..15) {
        val idx = (lz shl 4) or lx
        val wx = baseX + lx
        val wz = baseZ + lz
        edgeFlags[idx] = isEdge(wx, y, wz)
        wallFlags[idx] = isWall(wx, y, wz)
      }
    }

    for (lz in 0..15) {
      for (lx in 0..15) {
        val idx = (lz shl 4) or lx
        val wx = baseX + lx
        val wz = baseZ + lz

        var minEdge = MAX_DIST
        var minWall = MAX_DIST
        val dirMask = directionMask(wx, y, wz)

        for (dir in DX.indices) {
          if ((dirMask and (1 shl dir)) == 0) continue
          val dx = DX[dir]
          val dz = DZ[dir]

          var edgeDist = MAX_DIST
          for (d in 1..MAX_DIST) {
            val cx = lx + dx * d
            val cz = lz + dz * d

            val isEdgeHere = if (cx in 0..15 && cz in 0..15) {
              edgeFlags[(cz shl 4) or cx]
            } else {
              isEdge(wx + dx * d, y, wz + dz * d)
            }

            if (isEdgeHere) {
              edgeDist = d - 1
              break
            }
          }
          if (edgeDist < minEdge) minEdge = edgeDist

          var wallDist = MAX_DIST
          for (d in 1..MAX_DIST) {
            val cx = lx + dx * d
            val cz = lz + dz * d

            val isWallHere = if (cx in 0..15 && cz in 0..15) {
              wallFlags[(cz shl 4) or cx]
            } else {
              isWall(wx + dx * d, y, wz + dz * d)
            }

            if (isWallHere) {
              wallDist = d - 1
              break
            }
          }
          if (wallDist < minWall) minWall = wallDist

          if (minEdge == 0 && minWall == 0) break
        }

        // bits 0, 1, 2 = edge, bits 3, 4, 5 = wall, bit 6 = flag
        data[idx] = ((minEdge and 0x07) or ((minWall and 0x07) shl 3) or COMPUTED_FLAG.toInt()).toByte()
      }
    }

    chunk.setDistanceData(y, data)
  }

  private fun computePenalty(x: Int, y: Int, z: Int): Double {
    var minEdge = MAX_DIST
    var minWall = MAX_DIST
    val dirMask = directionMask(x, y, z)

    for (dir in DX.indices) {
      if ((dirMask and (1 shl dir)) == 0) continue
      val dx = DX[dir]
      val dz = DZ[dir]
      var cx = x + dx
      var cz = z + dz

      var edgeDist = MAX_DIST
      for (d in 1..MAX_DIST) {
        if (isEdge(cx, y, cz)) {
          edgeDist = d - 1
          break
        }
        cx += dx
        cz += dz
      }
      if (edgeDist < minEdge) minEdge = edgeDist

      cx = x + dx
      cz = z + dz
      var wallDist = MAX_DIST
      for (d in 1..MAX_DIST) {
        if (isWall(cx, y, cz)) {
          wallDist = d - 1
          break
        }
        cx += dx
        cz += dz
      }
      if (wallDist < minWall) minWall = wallDist

      if (minEdge == 0 && minWall == 0) break
    }

    return combinedPenalty(minEdge, minWall)
  }

  private fun combinedPenalty(edgeDist: Int, wallDist: Int): Double {
    val edgeIdx = edgeDist.coerceIn(0, OPEN_SPACE_SOFT_CAP)
    val wallIdx = wallDist.coerceIn(0, OPEN_SPACE_SOFT_CAP)
    return EDGE_PENALTIES[edgeIdx] + WALL_PENALTIES[wallIdx]
  }

  private fun directionMask(x: Int, y: Int, z: Int): Int {
    var mask = 0
    for (dir in DX.indices) {
      if (!isStepDirection(x, y, z, DX[dir], DZ[dir])) {
        mask = mask or (1 shl dir)
      }
    }
    return if (mask == 0) allDirMask else mask
  }

  private fun isStepDirection(x: Int, y: Int, z: Int, dx: Int, dz: Int): Boolean {
    val pre = ctx.precomputedData
    val nx = x + dx
    val nz = z + dz

    val stepUpState = ctx.get(nx, y, nz)
    if (isStepSurface(nx, y, nz, stepUpState) && pre.isSolid(nx, y, nz, stepUpState)) {
      val upFeet = ctx.get(nx, y + 1, nz)
      if (pre.isPassable(nx, y + 1, nz, upFeet)) {
        val upHead = ctx.get(nx, y + 2, nz)
        if (pre.isPassable(nx, y + 2, nz, upHead)) {
          return true
        }
      }
    }

    val stepDownState = ctx.get(nx, y - 2, nz)
    if (isStepSurface(nx, y - 2, nz, stepDownState) && pre.isSolid(nx, y - 2, nz, stepDownState)) {
      val downFeet = ctx.get(nx, y - 1, nz)
      if (pre.isPassable(nx, y - 1, nz, downFeet)) {
        val downHead = ctx.get(nx, y, nz)
        if (pre.isPassable(nx, y, nz, downHead)) {
          return true
        }
      }
    }

    return false
  }

  private fun isStepSurface(x: Int, y: Int, z: Int, state: BlockState): Boolean {
    val block = state.block
    if (block is StairsBlock) return state.get(StairsBlock.HALF) == BlockHalf.BOTTOM
    if (block is SlabBlock) return ctx.precomputedData.isBottomSlab(x, y, z, state)
    return false
  }

  private fun isEdge(x: Int, y: Int, z: Int): Boolean {
    val pre = ctx.precomputedData

    val current = ctx.get(x, y, z)
    if (pre.isSolid(x, y, z, current)) return false

    val below = ctx.get(x, y - 1, z)
    if (pre.isSolid(x, y - 1, z, below)) return false

    val below2 = ctx.get(x, y - 2, z)

    if (pre.isSolid(x, y - 2, z, below2)) return false

    return true
  }

  private fun isWall(x: Int, y: Int, z: Int): Boolean {
    val pre = ctx.precomputedData
    val headState = ctx.get(x, y + 1, z)

    if (pre.isBlockingWall(x, y + 1, z, headState)) return true

    if (pre.isSolid(x, y + 1, z, headState)) {
      val headBlock = headState.block
      if (headBlock is SlabBlock || headBlock is StairsBlock) {
        if (headBlock is StairsBlock && isStaircaseAbove(x, y, z, headState)) return false

        val feetBlock = ctx.get(x, y, z).block
        if (feetBlock is SlabBlock || feetBlock is StairsBlock) return false
        return true
      }

      return true
    }

    return pre.isBlockingWall(x, y, z, ctx.get(x, y, z))
  }

  private fun isStaircaseAbove(x: Int, y: Int, z: Int, headState: BlockState): Boolean {
    if (headState.get(StairsBlock.HALF) != BlockHalf.BOTTOM) return false

    for (dir in 0 until CARDINAL_DIR_COUNT) {
      val nx = x + DX[dir]
      val nz = z + DZ[dir]
      val belowState = ctx.get(nx, y, nz)
      if (belowState.block is StairsBlock && belowState.get(StairsBlock.HALF) == BlockHalf.BOTTOM) {
        return true
      }
    }

    return false
  }

  fun getEdgeDistance(x: Int, y: Int, z: Int): Int {
    var dist = MAX_DIST
    val dirMask = directionMask(x, y, z)
    for (dir in DX.indices) {
      if ((dirMask and (1 shl dir)) == 0) continue
      val d = scanForEdge(x, y, z, DX[dir], DZ[dir])
      if (d < dist) dist = d
      if (dist == 0) break
    }
    return dist
  }

  fun getWallDistance(x: Int, y: Int, z: Int): Int {
    var dist = MAX_DIST
    val dirMask = directionMask(x, y, z)
    for (dir in DX.indices) {
      if ((dirMask and (1 shl dir)) == 0) continue
      val d = scanForWall(x, y, z, DX[dir], DZ[dir])
      if (d < dist) dist = d
      if (dist == 0) break
    }
    return dist
  }

  private fun scanForEdge(x: Int, y: Int, z: Int, dx: Int, dz: Int): Int {
    var cx = x + dx
    var cz = z + dz
    for (d in 1..MAX_DIST) {
      if (isEdge(cx, y, cz)) return d - 1
      cx += dx
      cz += dz
    }
    return MAX_DIST
  }

  private fun scanForWall(x: Int, y: Int, z: Int, dx: Int, dz: Int): Int {
    var cx = x + dx
    var cz = z + dz
    for (d in 1..MAX_DIST) {
      if (isWall(cx, y, cz)) return d - 1
      cx += dx
      cz += dz
    }
    return MAX_DIST
  }

  fun clearCache() {
    penaltyCache.clear()
  }

}
