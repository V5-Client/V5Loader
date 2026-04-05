package com.v5.swift.nativepath

object NativePathfinderBridge {

  data class NativePathSearchRequest(
    val startPoints: IntArray,
    val endPoints: IntArray,
    val isFly: Boolean,
    val maxIterations: Int,
    val heuristicWeight: Double,
    val nonPrimaryStartPenalty: Double,
    val moveOrderOffset: Int,
    val avoidMeta: IntArray,
    val avoidPenalty: DoubleArray
  )

  data class NativeEtherwarpSearchRequest(
    val startX: Int,
    val startY: Int,
    val startZ: Int,
    val goalX: Int,
    val goalY: Int,
    val goalZ: Int,
    val maxIterations: Int,
    val threadCount: Int,
    val yawStep: Double,
    val pitchStep: Double,
    val newNodeCost: Double,
    val heuristicWeight: Double,
    val rayLength: Double,
    val rewireEpsilon: Double,
    val eyeHeight: Double
  )

  @Volatile
  private var lastError: String? = null

  @JvmStatic
  fun isAvailable(): Boolean = NativePathfinderJNI.isAvailable()

  @JvmStatic
  fun getLastError(): String? = lastError ?: NativePathfinderJNI.getLoadError()

  @JvmStatic
  fun setWorld(worldKey: String, minY: Int, maxY: Int) {
    if (!isAvailable()) {
      lastError = NativePathfinderJNI.getLoadError() ?: "Native pathfinder unavailable"
      return
    }

    try {
      NativePathfinderJNI.setWorld(worldKey, minY, maxY)
      lastError = null
    } catch (t: Throwable) {
      lastError = t.message ?: t.javaClass.simpleName
    }
  }

  @JvmStatic
  fun clearWorld() {
    if (!isAvailable()) return

    try {
      NativePathfinderJNI.clearWorld()
      lastError = null
    } catch (t: Throwable) {
      lastError = t.message ?: t.javaClass.simpleName
    }
  }

  @JvmStatic
  fun upsertChunk(
    chunkX: Int,
    chunkZ: Int,
    minY: Int,
    maxY: Int,
    sectionMask: Long,
    sectionFlags: ShortArray
  ) {
    if (!isAvailable()) return

    try {
      NativePathfinderJNI.upsertChunk(chunkX, chunkZ, minY, maxY, sectionMask, sectionFlags)
      lastError = null
    } catch (t: Throwable) {
      lastError = t.message ?: t.javaClass.simpleName
    }
  }

  @JvmStatic
  fun applyBlockUpdates(updates: IntArray) {
    if (!isAvailable()) return
    if (updates.isEmpty()) return

    try {
      NativePathfinderJNI.applyBlockUpdates(updates)
      lastError = null
    } catch (t: Throwable) {
      lastError = t.message ?: t.javaClass.simpleName
    }
  }

  @JvmStatic
  fun findPath(request: NativePathSearchRequest): NativePathResult? {
    if (!isAvailable()) {
      lastError = NativePathfinderJNI.getLoadError() ?: "Native pathfinder unavailable"
      return null
    }

    return try {
      val result = NativePathfinderJNI.findPath(
        request.startPoints,
        request.endPoints,
        request.isFly,
        request.maxIterations,
        request.heuristicWeight,
        request.nonPrimaryStartPenalty,
        request.moveOrderOffset,
        request.avoidMeta,
        request.avoidPenalty
      )

      if (result == null) {
        lastError = "Native pathfinder returned no path"
      } else {
        lastError = null
      }

      result
    } catch (t: Throwable) {
      lastError = t.message ?: t.javaClass.simpleName
      null
    }
  }

  @JvmStatic
  fun findEtherwarpPath(request: NativeEtherwarpSearchRequest): NativeEtherwarpResult? {
    if (!isAvailable()) {
      lastError = NativePathfinderJNI.getLoadError() ?: "Native pathfinder unavailable"
      return null
    }

    return try {
      val result = NativePathfinderJNI.findEtherwarpPath(
        request.startX,
        request.startY,
        request.startZ,
        request.goalX,
        request.goalY,
        request.goalZ,
        request.maxIterations,
        request.threadCount,
        request.yawStep,
        request.pitchStep,
        request.newNodeCost,
        request.heuristicWeight,
        request.rayLength,
        request.rewireEpsilon,
        request.eyeHeight
      )

      if (result == null) {
        lastError = "Native etherwarp pathfinder returned no path"
      } else {
        lastError = null
      }

      result
    } catch (t: Throwable) {
      lastError = t.message ?: t.javaClass.simpleName
      null
    }
  }

  @JvmStatic
  fun cancelSearch() {
    if (!isAvailable()) return

    try {
      NativePathfinderJNI.cancelSearch()
    } catch (_: Throwable) {
    }
  }
}
