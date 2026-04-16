package com.v5.swift.nativepath

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

object NativePathfinderJNI {

  private const val LIB_BASE = "V5PathJNI"

  @Volatile
  private var initialized = false

  @Volatile
  private var available = false

  @Volatile
  private var loadError: String? = null

  @JvmStatic
  fun initialize(): Boolean {
    if (initialized) return available

    synchronized(this) {
      if (initialized) return available

      try {
        val os = System.getProperty("os.name").lowercase()
        val (fileName, ext) = when {
          os.contains("win") -> "$LIB_BASE.dll" to ".dll"
          os.contains("linux") -> "$LIB_BASE.so" to ".so"
          os.contains("mac") -> "$LIB_BASE.dylib" to ".dylib"
          else -> throw IllegalStateException("Unsupported OS for native pathfinder: $os")
        }

        val resourcePath = "/assets/v5/$fileName"
        val input = NativePathfinderJNI::class.java.getResourceAsStream(resourcePath)
          ?: throw IllegalStateException("Native pathfinder library not found: $resourcePath")

        val tempFile: File = Files.createTempFile(LIB_BASE, ext).toFile().apply { deleteOnExit() }

        input.use { stream ->
          FileOutputStream(tempFile).use { out ->
            stream.copyTo(out)
          }
        }

        System.load(tempFile.absolutePath)

        if (!initNative()) {
          throw IllegalStateException("Native pathfinder initNative() returned false")
        }

        available = true
        loadError = null
      } catch (t: Throwable) {
        available = false
        loadError = t.message ?: t.javaClass.simpleName
      } finally {
        initialized = true
      }

      return available
    }
  }

  @JvmStatic
  fun isAvailable(): Boolean = initialize()

  @JvmStatic
  fun getLoadError(): String? = loadError

  @JvmStatic external fun initNative(): Boolean

  @JvmStatic external fun setWorld(worldKey: String, minY: Int, maxY: Int)
  @JvmStatic external fun clearWorld()

  @JvmStatic external fun upsertChunk(
    chunkX: Int,
    chunkZ: Int,
    minY: Int,
    maxY: Int,
    sectionMask: Long,
    sectionFlags: ShortArray
  )

  @JvmStatic external fun applyBlockUpdates(updates: IntArray)

  @JvmStatic external fun findPath(
    startPoints: IntArray,
    endPoints: IntArray,
    isFly: Boolean,
    maxIterations: Int,
    heuristicWeight: Double,
    nonPrimaryStartPenalty: Double,
    moveOrderOffset: Int,
    avoidMeta: IntArray,
    avoidPenalty: DoubleArray
  ): NativePathResult?

  @JvmStatic external fun findEtherwarpPath(
    goalX: Int,
    goalY: Int,
    goalZ: Int,
    startEyeX: Double,
    startEyeY: Double,
    startEyeZ: Double,
    maxIterations: Int,
    threadCount: Int,
    yawStep: Double,
    pitchStep: Double,
    newNodeCost: Double,
    heuristicWeight: Double,
    rayLength: Double,
    rewireEpsilon: Double,
    eyeHeight: Double
  ): NativeEtherwarpResult?

  @JvmStatic external fun cancelSearch()
}
