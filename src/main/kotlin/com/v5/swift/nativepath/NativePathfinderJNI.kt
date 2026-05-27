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

      val candidates = nativeResourceCandidates()
      val errors = mutableListOf<String>()

      for (resourcePath in candidates) {
        try {
          if (loadNativeFromResource(resourcePath)) {
            available = true
            loadError = null
            initialized = true
            return true
          }
          errors.add("$resourcePath: not found in jar")
        } catch (t: Throwable) {
          errors.add("$resourcePath: ${t.message ?: t.javaClass.simpleName}")
        }
      }

      available = false
      loadError = buildString {
        append("Failed to load native pathfinder")
        append(" (OS: ${System.getProperty("os.name")}, arch: ${System.getProperty("os.arch")})")
        append(". Tried:\n")
        errors.forEach { append("  - ").appendLine(it) }
      }.trimEnd()
      initialized = true
      return false
    }
  }

  private fun loadNativeFromResource(resourcePath: String): Boolean {
    val os = System.getProperty("os.name").lowercase()
    val ext = extensionForOs(os)

    val input = NativePathfinderJNI::class.java.getResourceAsStream(resourcePath)
      ?: return false

    val tempFile: File = Files.createTempFile(LIB_BASE, ext).toFile().apply { deleteOnExit() }

    input.use { stream ->
      FileOutputStream(tempFile).use { out ->
        stream.copyTo(out)
      }
    }

    System.load(tempFile.absolutePath)

    if (!initNative()) {
      throw IllegalStateException("initNative() returned false")
    }

    return true
  }

  private fun nativeResourceCandidates(): List<String> {
    val os = System.getProperty("os.name").lowercase()
    val arch = normalizeArch(System.getProperty("os.arch"))
    val ext = extensionForOs(os)
    val lib = "$LIB_BASE$ext"

    val platformPaths = when {
      os.contains("win") -> listOf("windows/$arch")
      os.contains("linux") -> when (arch) {
        "x86_64" -> listOf("linux/x86_64")
        else -> listOf("linux/$arch", "linux/x86_64")
      }
      os.contains("mac") -> when (arch) {
        "arm64" -> listOf("macos/arm64", "macos/universal")
        "x86_64" -> listOf("macos/x86_64", "macos/universal")
        else -> listOf("macos/$arch")
      }
      else -> emptyList()
    }

    return platformPaths.map { "/assets/v5/natives/$it/$lib" }
  }

  private fun extensionForOs(os: String): String = when {
    os.contains("win") -> ".dll"
    os.contains("mac") -> ".dylib"
    os.contains("linux") -> ".so"
    else -> throw IllegalStateException("Unsupported OS for native pathfinder: $os")
  }

  private fun normalizeArch(arch: String): String = when (arch.lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "arm64"
    else -> arch.lowercase()
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
