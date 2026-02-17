package dev.quiteboring.swift.finder.calculate

import dev.quiteboring.swift.finder.movement.CalculationContext
import kotlin.math.abs
import kotlin.math.floor
import net.minecraft.util.math.BlockPos

class Path(
  private val ctx: CalculationContext,
  endNode: PathNode,
  val timeTaken: Long,
  val nodesExplored: Int,
  private val isFly: Boolean = false
) {

  val points: List<BlockPos>
  val keyNodes: List<BlockPos>

  val nanosPerNode: Double get() = if (nodesExplored > 0) (timeTaken * 1_000_000.0) / nodesExplored else 0.0

  init {
    var curr: PathNode? = endNode
    val list = mutableListOf<BlockPos>()

    while (curr != null) {
      list.addFirst(BlockPos(curr.x, curr.y, curr.z))
      curr = curr.parent
    }

    points = list
    keyNodes = extractKeyPoints(points, epsilon = 1.7)
  }

  private fun extractKeyPoints(
    points: List<BlockPos>,
    epsilon: Double,
  ): List<BlockPos> {
    if (points.size <= 2) return points

    val result = ArrayList<BlockPos>()

    fun simplify(from: Int, to: Int) {
      if (to - from <= 1) {
        result.add(points[from])
        return
      }

      val start = points[from]
      val end = points[to]

      val dx = (end.x - start.x).toDouble()
      val dy = (end.y - start.y).toDouble()
      val dz = (end.z - start.z).toDouble()
      val lenSq = dx * dx + dy * dy + dz * dz

      var maxDistSq = 0.0
      var maxIndex = -1

      for (i in from + 1 until to) {
        val p = points[i]

        val distSq = if (lenSq == 0.0) {
          val ddx = (p.x - start.x).toDouble()
          val ddy = (p.y - start.y).toDouble()
          val ddz = (p.z - start.z).toDouble()
          ddx*ddx + ddy*ddy + ddz*ddz
        } else {
          val px = (p.x - start.x).toDouble()
          val py = (p.y - start.y).toDouble()
          val pz = (p.z - start.z).toDouble()

          val t = ((px * dx + py * dy + pz * dz) / lenSq).coerceIn(0.0, 1.0)

          val cx = start.x + dx * t
          val cy = start.y + dy * t
          val cz = start.z + dz * t

          (p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy) + (p.z - cz) * (p.z - cz)
        }

        if (distSq > maxDistSq) {
          maxDistSq = distSq
          maxIndex = i
        }
      }

      var canSimplify = maxDistSq <= epsilon * epsilon

      if (canSimplify) {
        canSimplify = if (isFly) {
          canFlyDirectly(start, end)
        } else {
          (from + 1 until to).all { i ->
            val p = points[i]
            ctx.safeCache.isSafe(p.x, p.y, p.z)
          }
        }
      }

      if (!canSimplify) {
        val splitIndex = if (maxIndex == -1) (from + to) / 2 else maxIndex
        simplify(from, splitIndex)
        simplify(splitIndex, to)
      } else {
        result.add(start)
      }
    }

    simplify(0, points.lastIndex)
    result.add(points.last())
    return result
  }

  private fun canFlyDirectly(from: BlockPos, to: BlockPos): Boolean {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val dz = to.z - from.z

    val steps = maxOf(abs(dx), abs(dy), abs(dz))
    if (steps <= 1) return true

    val pre = ctx.precomputedData

    for (i in 1 until steps) {
      val t = i.toDouble() / steps
      val x = floor(from.x + dx * t).toInt()
      val y = floor(from.y + dy * t).toInt()
      val z = floor(from.z + dz * t).toInt()

      if (!pre.isPassableForFlying(x, y, z)) return false
      if (!pre.isPassableForFlying(x, y + 1, z)) return false
    }

    return true
  }

}
