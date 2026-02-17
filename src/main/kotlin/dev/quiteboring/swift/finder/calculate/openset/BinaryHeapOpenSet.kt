package dev.quiteboring.swift.finder.calculate.openset

import dev.quiteboring.swift.finder.calculate.PathNode

class BinaryHeapOpenSet(initialSize: Int = 2048) {

  private var items = arrayOfNulls<PathNode>(initialSize)
  private var costs = DoubleArray(initialSize)

  @JvmField
  var size = 0

  fun add(node: PathNode) {
    val newSize = size + 1
    if (newSize >= items.size) {
      grow()
    }

    size = newSize
    node.heapPosition = newSize
    items[newSize] = node
    costs[newSize] = node.fCost
    siftUp(newSize)
  }

  private fun grow() {
    val newCapacity = items.size * 2
    items = items.copyOf(newCapacity)
    costs = costs.copyOf(newCapacity)
  }

  fun relocate(node: PathNode) {
    val pos = node.heapPosition
    costs[pos] = node.fCost
    siftUp(pos)
  }

  private fun siftUp(startPos: Int) {
    var pos = startPos
    val node = items[pos]!!
    val cost = costs[pos]

    while (pos > 1) {
      val parentPos = pos ushr 1
      val parentCost = costs[parentPos]

      if (cost >= parentCost) break

      val parent = items[parentPos]!!
      items[pos] = parent
      costs[pos] = parentCost
      parent.heapPosition = pos
      pos = parentPos
    }

    items[pos] = node
    costs[pos] = cost
    node.heapPosition = pos
  }

  fun poll(): PathNode {
    val result = items[1]!!
    result.heapPosition = -1

    if (size == 1) {
      items[1] = null
      size = 0
      return result
    }

    val lastPos = size
    val last = items[lastPos]!!
    val lastCost = costs[lastPos]

    items[lastPos] = null
    size = lastPos - 1

    items[1] = last
    costs[1] = lastCost
    last.heapPosition = 1
    siftDown()

    return result
  }

  private fun siftDown() {
    var pos = 1
    val node = items[1]!!
    val cost = costs[1]
    val halfSize = size ushr 1

    while (pos <= halfSize) {
      var childPos = pos shl 1
      var childCost = costs[childPos]

      val rightPos = childPos + 1
      if (rightPos <= size) {
        val rightCost = costs[rightPos]
        if (rightCost < childCost) {
          childPos = rightPos
          childCost = rightCost
        }
      }

      if (cost <= childCost) break

      val child = items[childPos]!!
      items[pos] = child
      costs[pos] = childCost
      child.heapPosition = pos
      pos = childPos
    }

    items[pos] = node
    costs[pos] = cost
    node.heapPosition = pos
  }

  fun isEmpty() = size == 0

  fun isNotEmpty() = size > 0

  fun clear() {
    @Suppress("EmptyRange")
    for (i in 1..size) { // intelliJ wants to change to downTo. That's because the IDE doesn't know that size will grow. Do not change to downTo.
      items[i]?.heapPosition = -1
      items[i] = null
    }

    size = 0
  }

}
