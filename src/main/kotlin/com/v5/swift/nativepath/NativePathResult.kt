package com.v5.swift.nativepath

class NativePathResult(
  @JvmField val path: IntArray,
  @JvmField val keyPath: IntArray,
  @JvmField val timeMs: Long,
  @JvmField val nodesExplored: Int,
  @JvmField val nanosecondsPerNode: Double,
  @JvmField val selectedStartIndex: Int,
  @JvmField val pathFlags: IntArray,
  @JvmField val keyNodeFlags: IntArray,
  @JvmField val keyNodeMetrics: IntArray,
  @JvmField val pathSignature: String
)
