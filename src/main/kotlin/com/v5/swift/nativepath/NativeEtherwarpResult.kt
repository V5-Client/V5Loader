package com.v5.swift.nativepath

class NativeEtherwarpResult(
  @JvmField val path: IntArray,
  @JvmField val angles: FloatArray,
  @JvmField val timeMs: Long,
  @JvmField val nodesExplored: Int,
  @JvmField val nanosecondsPerNode: Double
)
