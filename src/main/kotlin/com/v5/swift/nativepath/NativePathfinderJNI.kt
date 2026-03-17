package com.v5.swift.nativepath

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object NativePathfinderJNI {

  private const val LIB_BASE = "V5PathJNI"
  private const val CACHE_NAMESPACE = "v5/native-pathfinder/v2"

  @Volatile
  private var initialized = false

  @Volatile
  private var available = false

  @Volatile
  private var loadError: String? = null

  @Volatile
  private var lastLoadResult: LoadResult? = null

  @JvmStatic
  fun initialize(): Boolean {
    if (initialized) return available

    synchronized(this) {
      if (initialized) return available

      try {
        val runtime = detectRuntime()
        lastLoadResult = null
        val loadResult = loadNativeLibrary(runtime)
        lastLoadResult = loadResult
        System.load(loadResult.cacheFile.toAbsolutePath().toString())

        if (!initNative()) {
          throw IllegalStateException("Native pathfinder initNative() returned false")
        }

        available = true
        loadError = null
      } catch (t: Throwable) {
        available = false
        loadError = buildLoadError(t)
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

  @JvmStatic external fun cancelSearch()

  private data class RuntimePlatform(
    val osTag: String,
    val archTag: String,
    val libraryExtension: String
  ) {
    val cacheTag: String get() = "$osTag-$archTag"
  }

  private data class LoadResult(
    val runtime: RuntimePlatform,
    val resourcePath: String,
    val cacheFile: Path
  )

  private fun detectRuntime(): RuntimePlatform {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val archName = System.getProperty("os.arch").orEmpty().lowercase()

    val osTag = when {
      osName.contains("win") -> "windows"
      osName.contains("mac") || osName.contains("darwin") -> "macos"
      osName.contains("nux") || osName.contains("nix") || osName.contains("aix") -> "linux"
      else -> throw IllegalStateException("Unsupported operating system for native pathfinder: ${System.getProperty("os.name")}")
    }

    val archTag = when (archName) {
      "amd64", "x86_64" -> "x86_64"
      "aarch64", "arm64" -> "aarch64"
      "x86", "i386", "i486", "i586", "i686" -> "x86"
      else -> archName.ifBlank { "unknown" }
    }

    val libraryExtension = when (osTag) {
      "windows" -> ".dll"
      "linux" -> ".so"
      "macos" -> ".dylib"
      else -> throw IllegalStateException("Unsupported operating system for native pathfinder: $osTag")
    }

    return RuntimePlatform(osTag, archTag, libraryExtension)
  }

  private fun candidateResourcePaths(runtime: RuntimePlatform): List<String> {
    val candidates = mutableListOf<String>()
    val ext = runtime.libraryExtension

    when (runtime.osTag) {
      "windows" -> {
        candidates += "/assets/v5/$LIB_BASE-windows-${runtime.archTag}$ext"
        candidates += "/assets/v5/$LIB_BASE-windows$ext"
      }
      "linux" -> {
        candidates += "/assets/v5/$LIB_BASE-linux-${runtime.archTag}$ext"
        candidates += "/assets/v5/$LIB_BASE-linux$ext"
      }
      "macos" -> {
        candidates += "/assets/v5/$LIB_BASE-macos-universal$ext"
        candidates += "/assets/v5/$LIB_BASE-macos-${runtime.archTag}$ext"
        candidates += "/assets/v5/$LIB_BASE-macos$ext"
      }
    }

    candidates += "/assets/v5/$LIB_BASE$ext"
    return candidates.distinct()
  }

  private fun loadNativeLibrary(runtime: RuntimePlatform): LoadResult {
    val candidates = candidateResourcePaths(runtime)
    val attempts = mutableListOf<String>()
    val cacheRoot = FabricLoader.getInstance().configDir.resolve(CACHE_NAMESPACE)
    Files.createDirectories(cacheRoot)

    for (resourcePath in candidates) {
      attempts += resourcePath
      val stream = NativePathfinderJNI::class.java.getResourceAsStream(resourcePath) ?: continue
      stream.use {
        val libraryBytes = it.readBytes()
        val digest = sha256Hex(libraryBytes)
        val cacheFile = cacheRoot.resolve("$LIB_BASE-${runtime.cacheTag}-$digest${runtime.libraryExtension}")

        if (Files.notExists(cacheFile)) {
          val tempFile = Files.createTempFile(cacheRoot, "$LIB_BASE-", ".tmp")
          try {
            Files.write(tempFile, libraryBytes)
            try {
              Files.move(
                tempFile,
                cacheFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
              )
            } catch (_: AtomicMoveNotSupportedException) {
              Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
              Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
          } finally {
            try {
              Files.deleteIfExists(tempFile)
            } catch (_: Exception) {
            }
          }
        }

        return LoadResult(runtime, resourcePath, cacheFile)
      }
    }

    throw IllegalStateException(
      buildString {
        appendLine("Native pathfinder library not found for ${runtime.osTag}/${runtime.archTag}.")
        appendLine("Attempted resources:")
        attempts.forEach { appendLine("  - $it") }
      }
    )
  }

  private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun buildLoadError(cause: Throwable): String {
    return buildString {
      appendLine("Failed to initialize V5PathJNI.")
      appendLine("os.name=${System.getProperty("os.name")}")
      appendLine("os.arch=${System.getProperty("os.arch")}")
      appendLine("java.version=${System.getProperty("java.version")}")
      appendLine("java.vm.name=${System.getProperty("java.vm.name")}")
      appendLine("configDir=${FabricLoader.getInstance().configDir}")
      lastLoadResult?.let {
        appendLine("resolvedRuntime=${it.runtime.osTag}/${it.runtime.archTag}")
        appendLine("resourcePath=${it.resourcePath}")
        appendLine("cacheFile=${it.cacheFile}")
      }
      appendLine("cause=${cause.javaClass.name}: ${cause.message ?: "no message"}")
      cause.stackTraceToString().takeIf { it.isNotBlank() }?.let {
        appendLine("stacktrace:")
        append(it)
      }
    }
  }
}
