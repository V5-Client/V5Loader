package com.v5.swift.finder.calculate.path

import com.v5.swift.cache.CachedWorld
import com.v5.swift.finder.calculate.Path
import com.v5.swift.finder.calculate.PathNode
import com.v5.swift.finder.goal.GoalFly
import com.v5.swift.finder.goal.IGoal
import com.v5.swift.finder.movement.CalculationContext
import com.v5.swift.finder.movement.MovementResult
import com.v5.swift.finder.movement.Moves
import com.v5.swift.finder.movement.MovesFly
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

/**
 * Thank you EpsilonPhoenix for this superb class!
 */
class AStarPathfinder(
    private val startPoints: Array<IntArray>,
    private val goal: IGoal,
    private val ctx: CalculationContext,
    private val maxIterations: Int = 500_000,
    private val heuristicWeight: Double = 1.05,
    private val nonPrimaryStartPenalty: Double = 0.0,
    private val isFly: Boolean = false,
) {

  private companion object {
    private const val INITIAL_NODE_CAPACITY = 16_384
    private const val INITIAL_HEAP_CAPACITY = 4_096
    private const val COORD_MAP_INITIAL_CAPACITY = 16_384
    private const val COORD_MAP_LOAD_FACTOR = 0.5f
    private const val UNVISITED_COST = 1e6
  }

  private var nodeCount = 0

  private var nodeX = IntArray(INITIAL_NODE_CAPACITY)
  private var nodeY = IntArray(INITIAL_NODE_CAPACITY)
  private var nodeZ = IntArray(INITIAL_NODE_CAPACITY)

  private var nodeGCost = DoubleArray(INITIAL_NODE_CAPACITY)
  private var nodeHCost = DoubleArray(INITIAL_NODE_CAPACITY)
  private var nodeFCost = DoubleArray(INITIAL_NODE_CAPACITY)

  private var nodeParent = IntArray(INITIAL_NODE_CAPACITY) { -1 }

  private var nodeHeapPos = IntArray(INITIAL_NODE_CAPACITY) { -1 }

  private val coordToNode = Long2IntOpenHashMap(COORD_MAP_INITIAL_CAPACITY, COORD_MAP_LOAD_FACTOR).apply {
    defaultReturnValue(-1)
  }

  private fun ensureCapacity(required: Int) {
    val capacity = nodeX.size
    if (required <= capacity) return
    val newCapacity = maxOf(capacity * 2, required)
    nodeX = nodeX.copyOf(newCapacity)
    nodeY = nodeY.copyOf(newCapacity)
    nodeZ = nodeZ.copyOf(newCapacity)
    nodeGCost = nodeGCost.copyOf(newCapacity)
    nodeHCost = nodeHCost.copyOf(newCapacity)
    nodeFCost = nodeFCost.copyOf(newCapacity)
    nodeParent = nodeParent.copyOf(newCapacity).also { it.fill(-1, capacity, newCapacity) }
    nodeHeapPos = nodeHeapPos.copyOf(newCapacity).also { it.fill(-1, capacity, newCapacity) }
  }

  private fun createNode(x: Int, y: Int, z: Int): Int {
    ensureCapacity(nodeCount + 1)
    val idx = nodeCount++
    nodeX[idx] = x
    nodeY[idx] = y
    nodeZ[idx] = z
    nodeGCost[idx] = UNVISITED_COST
    nodeHCost[idx] = goal.heuristic(x, y, z)
    nodeFCost[idx] = UNVISITED_COST
    coordToNode.put(PathNode.Companion.coordKey(x, y, z), idx)
    return idx
  }

  private fun seedStartNodes(openSet: PooledHeap, weight: Double) {
    for ((index, point) in startPoints.withIndex()) {
      if (point.size < 3) continue
      val x = point[0]
      val y = point[1]
      val z = point[2]
      val startPenalty = if (index == 0) 0.0 else nonPrimaryStartPenalty.coerceAtLeast(0.0)
      val nodeKey = PathNode.Companion.coordKey(x, y, z)

      var nodeIdx = coordToNode.get(nodeKey)
      if (nodeIdx == -1) {
        nodeIdx = createNode(x, y, z)
      }

      if (startPenalty < nodeGCost[nodeIdx]) {
        nodeParent[nodeIdx] = -1
        nodeGCost[nodeIdx] = startPenalty
        nodeFCost[nodeIdx] = startPenalty + nodeHCost[nodeIdx] * weight
      }

      if (nodeHeapPos[nodeIdx] == -1) {
        openSet.add(nodeIdx)
      } else {
        openSet.relocate(nodeIdx)
      }
    }
  }

  fun findPath(): Path? {
    CachedWorld.waitForLoad()
    if (Thread.currentThread().isInterrupted) {
      Thread.currentThread().interrupt()
      return null
    }

    if (startPoints.isEmpty() || startPoints.any { it.size < 3 }) {
      return null
    }
    if (maxIterations <= 0) {
      return null
    }

    if (isFly && startPoints.size != 1) {
      return null
    }

    if (isFly && goal is GoalFly) {
      val start = startPoints[0]
      ctx.setFlightParameters(
        start[0], start[1], start[2],
        goal.goalX, goal.goalY, goal.goalZ
      )
    }

    val openSet = PooledHeap(this)
    val weight = heuristicWeight

    seedStartNodes(openSet, weight)

    val res = MovementResult()
    val moves = if (isFly) MovesFly.entries else Moves.entries
    val infCost = ctx.cost.INF_COST

    val startTime = System.nanoTime()
    var iterations = 0

    while (openSet.isNotEmpty() && iterations < maxIterations) {
      if (Thread.currentThread().isInterrupted) {
        Thread.currentThread().interrupt()
        return null
      }

      iterations++

      val currIdx = openSet.poll()
      if (currIdx < 0) break
      val currX = nodeX[currIdx]
      val currY = nodeY[currIdx]
      val currZ = nodeZ[currIdx]

      if (goal.isAtGoal(currX, currY, currZ)) {
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        return buildPath(currIdx, elapsed, iterations)
      }

      val currGCost = nodeGCost[currIdx]
      for (move in moves) {
        res.cost = infCost
        move.calculate(ctx, currX, currY, currZ, res)

        val moveCost = res.cost
        if (moveCost >= infCost) continue

        val newCost = currGCost + moveCost
        val neighbourKey = PathNode.Companion.coordKey(res.x, res.y, res.z)
        var neighbourIdx = coordToNode.get(neighbourKey)

        if (neighbourIdx == -1) {
          neighbourIdx = createNode(res.x, res.y, res.z)
          nodeParent[neighbourIdx] = currIdx
          nodeGCost[neighbourIdx] = newCost
          nodeFCost[neighbourIdx] = newCost + nodeHCost[neighbourIdx] * weight
          openSet.add(neighbourIdx)
        } else if (newCost < nodeGCost[neighbourIdx]) {
          nodeParent[neighbourIdx] = currIdx
          nodeGCost[neighbourIdx] = newCost
          nodeFCost[neighbourIdx] = newCost + nodeHCost[neighbourIdx] * weight

          if (nodeHeapPos[neighbourIdx] != -1) {
            openSet.relocate(neighbourIdx)
          } else {
            openSet.add(neighbourIdx)
          }
        }
      }
    }

    return null
  }

  private fun buildPath(endIdx: Int, elapsed: Long, iterations: Int): Path {
    val endNode = PathNode(nodeX[endIdx], nodeY[endIdx], nodeZ[endIdx], goal)
    endNode.gCost = nodeGCost[endIdx]

    var currIdx = nodeParent[endIdx]
    var childNode = endNode

    while (currIdx != -1) {
      val node = PathNode(nodeX[currIdx], nodeY[currIdx], nodeZ[currIdx], goal)
      node.gCost = nodeGCost[currIdx]
      childNode.parent = node
      childNode = node
      currIdx = nodeParent[currIdx]
    }

    return Path(ctx, endNode, elapsed, iterations, isFly)
  }

  private class PooledHeap(private val pf: AStarPathfinder) {
    private var items = IntArray(INITIAL_HEAP_CAPACITY)
    private var size = 0

    fun add(nodeIdx: Int) {
      val newSize = size + 1
      if (newSize >= items.size) {
        items = items.copyOf(items.size * 2)
      }
      size = newSize
      pf.nodeHeapPos[nodeIdx] = newSize
      items[newSize] = nodeIdx
      siftUp(newSize)
    }

    fun relocate(nodeIdx: Int) {
      val pos = pf.nodeHeapPos[nodeIdx]
      if (pos <= 0) return
      siftUp(pos)
      siftDown(pos)
    }

    private fun siftUp(startPos: Int) {
      var pos = startPos
      val nodeIdx = items[pos]
      val cost = pf.nodeFCost[nodeIdx]

      while (pos > 1) {
        val parentPos = pos ushr 1
        val parentIdx = items[parentPos]
        val parentCost = pf.nodeFCost[parentIdx]

        if (cost >= parentCost) break

        items[pos] = parentIdx
        pf.nodeHeapPos[parentIdx] = pos
        pos = parentPos
      }

      items[pos] = nodeIdx
      pf.nodeHeapPos[nodeIdx] = pos
    }

    fun poll(): Int {
      if (size <= 0) return -1
      val result = items[1]
      pf.nodeHeapPos[result] = -1

      if (size == 1) {
        size = 0
        return result
      }

      val lastIdx = items[size]
      size--
      items[1] = lastIdx
      pf.nodeHeapPos[lastIdx] = 1
      siftDown()

      return result
    }

    private fun siftDown(startPos: Int = 1) {
      var pos = startPos
      val nodeIdx = items[pos]
      val cost = pf.nodeFCost[nodeIdx]
      val halfSize = size ushr 1

      while (pos <= halfSize) {
        var childPos = pos shl 1
        var childIdx = items[childPos]
        var childCost = pf.nodeFCost[childIdx]

        val rightPos = childPos + 1
        if (rightPos <= size) {
          val rightIdx = items[rightPos]
          val rightCost = pf.nodeFCost[rightIdx]
          if (rightCost < childCost) {
            childPos = rightPos
            childIdx = rightIdx
            childCost = rightCost
          }
        }

        if (cost <= childCost) break

        items[pos] = childIdx
        pf.nodeHeapPos[childIdx] = pos
        pos = childPos
      }

      items[pos] = nodeIdx
      pf.nodeHeapPos[nodeIdx] = pos
    }

    fun isNotEmpty() = size > 0
  }

}
