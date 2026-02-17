package dev.quiteboring.swift.finder.calculate

import dev.quiteboring.swift.finder.goal.IGoal

class PathNode(
  @JvmField val x: Int,
  @JvmField val y: Int,
  @JvmField val z: Int,
  goal: IGoal,
) {

  @JvmField var gCost: Double = 1e6
  @JvmField val hCost: Double = goal.heuristic(x, y, z)
  @JvmField var fCost: Double = 1e6
  @JvmField var heapPosition = -1
  @JvmField var parent: PathNode? = null

  override fun equals(other: Any?): Boolean {
    if (other !is PathNode) return false
    return other.x == x && other.y == y && other.z == z
  }

  override fun hashCode(): Int = (x * 73856093) xor (y * 19349663) xor (z * 83492791)

  companion object {
    @JvmStatic
    fun coordKey(x: Int, y: Int, z: Int): Long {
      val px = (x + 33554432).toLong() and 0x3FFFFFF
      val py = (y + 2048).toLong() and 0xFFF
      val pz = (z + 33554432).toLong() and 0x3FFFFFF
      return (px shl 38) or (py shl 26) or pz
    }
  }
}
