package com.chattriggers.ctjs.api.world.pathfinding

class NativeEtherwarpResult(
  @JvmField val path: IntArray,
  @JvmField val angles: FloatArray,
  @JvmField val timeMs: Long,
  @JvmField val nodesExplored: Int,
  @JvmField val nanosecondsPerNode: Double
)
