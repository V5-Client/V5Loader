package com.chattriggers.ctjs.api.world.pathfinding

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
    val goalX: Int,
    val goalY: Int,
    val goalZ: Int,
    val startEyeX: Double,
    val startEyeY: Double,
    val startEyeZ: Double,
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

  private fun setUnavailableError() {
    lastError = NativePathfinderJNI.getLoadError() ?: "Native pathfinder unavailable"
  }

  private fun setError(t: Throwable) {
    lastError = t.message ?: t.javaClass.simpleName
  }

  private inline fun runNative(block: () -> Unit) {
    if (!isAvailable()) {
      setUnavailableError()
      return
    }

    try {
      block()
      lastError = null
    } catch (t: Throwable) {
      setError(t)
    }
  }

  private inline fun <T> callNative(noResultError: String, block: () -> T?): T? {
    if (!isAvailable()) {
      setUnavailableError()
      return null
    }

    return try {
      block().also {
        lastError = if (it == null) noResultError else null
      }
    } catch (t: Throwable) {
      setError(t)
      null
    }
  }

  @JvmStatic
  fun setWorld(worldKey: String, minY: Int, maxY: Int) =
    runNative { NativePathfinderJNI.setWorld(worldKey, minY, maxY) }

  @JvmStatic
  fun clearWorld() =
    runNative { NativePathfinderJNI.clearWorld() }

  @JvmStatic
  fun upsertChunk(
    chunkX: Int,
    chunkZ: Int,
    minY: Int,
    maxY: Int,
    sectionMask: Long,
    sectionFlags: ShortArray
  ) = runNative {
    NativePathfinderJNI.upsertChunk(chunkX, chunkZ, minY, maxY, sectionMask, sectionFlags)
  }

  @JvmStatic
  fun upsertChunks(
    metadata: IntArray,
    sectionMasks: LongArray,
    sectionFlags: Array<ShortArray>
  ) = runNative {
    NativePathfinderJNI.upsertChunks(metadata, sectionMasks, sectionFlags)
  }

  @JvmStatic
  fun applyBlockUpdates(updates: IntArray) {
    if (updates.isEmpty()) return

    runNative { NativePathfinderJNI.applyBlockUpdates(updates) }
  }

  @JvmStatic
  fun findPath(request: NativePathSearchRequest): NativePathResult? =
    callNative("Native pathfinder returned no path") {
      NativePathfinderJNI.findPath(
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
    }

  @JvmStatic
  fun findEtherwarpPath(request: NativeEtherwarpSearchRequest): NativeEtherwarpResult? =
    callNative("Native etherwarp pathfinder returned no path") {
      NativePathfinderJNI.findEtherwarpPath(
        request.goalX,
        request.goalY,
        request.goalZ,
        request.startEyeX,
        request.startEyeY,
        request.startEyeZ,
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
