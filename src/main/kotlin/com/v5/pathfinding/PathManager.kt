package com.v5.pathfinding

import com.chattriggers.ctjs.api.message.ChatLib
import com.chattriggers.ctjs.api.world.TabList
import com.v5.swift.Swift
import com.v5.swift.nativepath.NativePathfinderBridge
import com.v5.swift.nativepath.NativeStateEncoder
import com.v5.swift.nativepath.NativeVoxelFlags
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos

object PathManager {
  private const val NON_PRIMARY_START_PENALTY = 250.0
  private const val HEURISTIC_WEIGHT = 1.05
  private const val ETHERWARP_DEFAULT_MAX_ITERATIONS = 100_000
  private const val ETHERWARP_AUTO_THREAD_COUNT = 0
  private const val ETHERWARP_STANDING_EYE_HEIGHT = 2.62
  private const val ETHERWARP_LEGACY_SNEAK_OFFSET = 0.08
  private const val ETHERWARP_MODERN_SNEAK_OFFSET = 0.35

  private val MODERN_ETHERWARP_AREAS = setOf(
    "Hub",
    "Dwarven Mines",
    "Gold Mine",
    "The Park",
    "Park",
    "Spider's Den",
    "Spider Den",
    "The End",
    "End",
    "The Farming Islands",
    "The Barn",
    "Galatea"
  )

  const val FLAG_FLUID_FEET = 1 shl 0
  const val FLAG_FLUID_HEAD = 1 shl 1
  const val FLAG_LOW_HEADROOM = 1 shl 2
  const val FLAG_NEAR_EDGE = 1 shl 3
  const val FLAG_NEAR_WALL = 1 shl 4
  const val FLAG_STEP_UP_NEXT = 1 shl 5
  const val FLAG_DROP_NEXT = 1 shl 6
  const val FLAG_TIGHT_CORRIDOR = 1 shl 7

  private data class PathAnnotations(
    val pathFlags: IntArray,
    val keyNodeFlags: IntArray,
    val keyNodeMetrics: IntArray,
    val signatureHex: String
  )

  private data class ManagedAvoidEntry(
    val x: Int,
    val y: Int,
    val z: Int,
    val radius: Int,
    var penalty: Double,
    var ttlSearches: Int
  )

  private data class NativeAvoidZone(
    val x: Int,
    val y: Int,
    val z: Int,
    val radiusSq: Int,
    val penalty: Double,
    val maxYDiff: Int
  )

  private data class PathSnapshot(
    val points: List<BlockPos>,
    val keyNodes: List<BlockPos>,
    val isFly: Boolean,
    val timeMs: Long,
    val nodesExplored: Int,
    val nanosecondsPerNode: Double,
    val selectedStartIndex: Int
  )

  private data class EtherwarpSnapshot(
    val points: List<BlockPos>,
    val angles: FloatArray,
    val timeMs: Long,
    val nodesExplored: Int,
    val nanosecondsPerNode: Double
  )

  @Volatile
  private var currentPath: PathSnapshot? = null

  @Volatile
  private var currentAnnotations: PathAnnotations? = null

  @Volatile
  private var currentEtherwarpPath: EtherwarpSnapshot? = null

  @Volatile
  private var lastError: String? = null

  @Volatile
  private var isSearching: Boolean = false

  @Volatile
  private var searchVariantSeed: Int = 0

  private val avoidLock = Any()
  private val transientAvoidEntries = ArrayList<ManagedAvoidEntry>(8)

  private var currentTask: Future<*>? = null
  private val searchId = AtomicInteger(0)

  private fun resolveEtherwarpThreadCount(threadCount: Int): Int {
    val maxThreads = max(1, Runtime.getRuntime().availableProcessors())
    if (threadCount == ETHERWARP_AUTO_THREAD_COUNT) {
      return maxThreads
    }

    return threadCount.coerceIn(1, maxThreads)
  }

  @JvmStatic
  @JvmOverloads
  fun findPath(
    startX: Int,
    startY: Int,
    startZ: Int,
    endX: Int,
    endY: Int,
    endZ: Int,
    maxIterations: Int = 500_000,
    isFly: Boolean = false
  ): Boolean {
    return findPath(
      arrayOf(intArrayOf(startX, startY, startZ)),
      arrayOf(intArrayOf(endX, endY, endZ)),
      maxIterations,
      isFly
    )
  }

  @JvmStatic
  @JvmOverloads
  fun findPathMultipleGoals(
    startX: Int,
    startY: Int,
    startZ: Int,
    endGoals: IntArray,
    maxIterations: Int = 500_000,
    isFly: Boolean = false
  ): Boolean {
    val endPoints = unpackFlatPoints("End goals", endGoals) ?: return false
    return findPath(
      arrayOf(intArrayOf(startX, startY, startZ)),
      endPoints,
      maxIterations,
      isFly
    )
  }

  @JvmStatic
  @JvmOverloads
  fun findFlyPath(startPoints: Array<IntArray>, endPoints: Array<IntArray>, maxIterations: Int = 500_000): Boolean {
    return findPath(startPoints, endPoints, maxIterations, isFly = true)
  }

  @JvmStatic
  @JvmOverloads
  fun findPath(startPoints: Array<IntArray>, endPoints: Array<IntArray>, maxIterations: Int = 500_000, isFly: Boolean = false): Boolean {
    cancelSearch()

    lastError = null
    currentPath = null
    currentAnnotations = null
    currentEtherwarpPath = null

    if (maxIterations <= 0) {
      lastError = "maxIterations must be > 0"
      return false
    }

    val startValidation = validatePoints("Start points", startPoints)
    if (startValidation != null) {
      lastError = startValidation
      return false
    }

    val endValidation = validatePoints("End points", endPoints)
    if (endValidation != null) {
      lastError = endValidation
      return false
    }

    if (isFly && (startPoints.size != 1 || endPoints.size != 1)) {
      lastError = "Fly pathfinder only supports one start point and one end point"
      return false
    }

    val nativeValidation = validateNativeAvailability()
    if (nativeValidation != null) {
      lastError = nativeValidation
      return false
    }

    val heightValidation = validateHeights(startPoints, endPoints, isFly)
    if (heightValidation != null) {
      lastError = heightValidation
      return false
    }

    val avoidZones = consumeTransientAvoidZones()
    val (avoidMeta, avoidPenalty) = encodeAvoidZones(avoidZones)

    val currentId = searchId.incrementAndGet()
    isSearching = true

    val startFlat = flattenPoints(startPoints)
    val endFlat = flattenPoints(endPoints)

    try {
      currentTask = Swift.executor.submit {
        try {
          val result = NativePathfinderBridge.findPath(
            NativePathfinderBridge.NativePathSearchRequest(
              startFlat,
              endFlat,
              isFly,
              maxIterations,
              HEURISTIC_WEIGHT,
              if (isFly) 0.0 else NON_PRIMARY_START_PENALTY,
              if (isFly) 0 else searchVariantSeed,
              avoidMeta,
              avoidPenalty
            )
          )

          if (searchId.get() != currentId) {
            return@submit
          }

          if (result != null && result.path.isNotEmpty()) {
            val points = toBlockPosList(result.path)
            val keyNodes = toBlockPosList(if (result.keyPath.isNotEmpty()) result.keyPath else result.path)

            currentPath = PathSnapshot(
              points = points,
              keyNodes = keyNodes,
              isFly = isFly,
              timeMs = result.timeMs,
              nodesExplored = result.nodesExplored,
              nanosecondsPerNode = result.nanosecondsPerNode,
              selectedStartIndex = result.selectedStartIndex
            )
            currentAnnotations = PathAnnotations(
              pathFlags = result.pathFlags,
              keyNodeFlags = result.keyNodeFlags,
              keyNodeMetrics = result.keyNodeMetrics,
              signatureHex = result.pathSignature
            )
            lastError = null
          } else {
            currentPath = null
            currentAnnotations = null
            lastError = NativePathfinderBridge.getLastError() ?: "No path found to destination"
          }
        } catch (e: InterruptedException) {
          if (searchId.get() == currentId) {
            lastError = "Pathfinding was cancelled"
          }
          Thread.currentThread().interrupt()
        } catch (e: Exception) {
          if (searchId.get() == currentId) {
            lastError = e.message ?: "Unknown error during native pathfinding"
            e.printStackTrace()
          }
        } finally {
          if (searchId.get() == currentId) {
            isSearching = false
            currentTask = null
          }
        }
      }
    } catch (e: Exception) {
      if (searchId.get() == currentId) {
        isSearching = false
        lastError = e.message ?: "Failed to submit native pathfinding task"
      }
      return false
    }

    return true
  }

  @JvmStatic
  @JvmOverloads
  fun findEtherwarpPath(
    startX: Int,
    startY: Int,
    startZ: Int,
    goalX: Int,
    goalY: Int,
    goalZ: Int,
    maxIterations: Int = ETHERWARP_DEFAULT_MAX_ITERATIONS,
    threadCount: Int = ETHERWARP_AUTO_THREAD_COUNT,
    yawStep: Double = 5.0,
    pitchStep: Double = 5.0,
    newNodeCost: Double = 1.0,
    heuristicWeight: Double = 1.0,
    rayLength: Double = 61.0,
    rewireEpsilon: Double = 1.0,
    eyeHeight: Double = Double.NaN
  ): Boolean {
    cancelSearch()

    lastError = null
    currentPath = null
    currentAnnotations = null
    currentEtherwarpPath = null

    if (maxIterations <= 0) {
      lastError = "maxIterations must be > 0"
      return false
    }
    if (threadCount < ETHERWARP_AUTO_THREAD_COUNT) {
      lastError = "threadCount must be >= 0 (0 = auto)"
      return false
    }
    if (!yawStep.isFinite() || yawStep <= 0.0) {
      lastError = "yawStep must be > 0"
      return false
    }
    if (!pitchStep.isFinite() || pitchStep <= 0.0) {
      lastError = "pitchStep must be > 0"
      return false
    }
    if (!newNodeCost.isFinite() || newNodeCost <= 0.0) {
      lastError = "newNodeCost must be > 0"
      return false
    }
    if (!heuristicWeight.isFinite() || heuristicWeight <= 0.0) {
      lastError = "heuristicWeight must be > 0"
      return false
    }
    if (!rayLength.isFinite() || rayLength <= 0.0) {
      lastError = "rayLength must be > 0"
      return false
    }
    if (!rewireEpsilon.isFinite() || rewireEpsilon < 0.0) {
      lastError = "rewireEpsilon must be >= 0"
      return false
    }
    val resolvedEyeHeight = when {
      eyeHeight.isNaN() -> getCurrentEtherwarpEyeHeight()
      eyeHeight.isFinite() && eyeHeight > 0.0 -> eyeHeight
      else -> Double.NaN
    }
    if (!resolvedEyeHeight.isFinite() || resolvedEyeHeight <= 0.0) {
      lastError = "eyeHeight must be > 0 or NaN for auto"
      return false
    }

    val nativeValidation = validateNativeAvailability()
    if (nativeValidation != null) {
      lastError = nativeValidation
      return false
    }

    val world = MinecraftClient.getInstance().world ?: run {
      lastError = "World is not loaded"
      return false
    }
    val minSupportY = world.bottomY
    val maxSupportY = world.topYInclusive - 2
    if (startY !in minSupportY..maxSupportY) {
      lastError = "Etherwarp start Y must be between $minSupportY and $maxSupportY"
      return false
    }
    if (goalY !in minSupportY..maxSupportY) {
      lastError = "Etherwarp goal Y must be between $minSupportY and $maxSupportY"
      return false
    }

    validateEtherwarpLanding("Start block", startX, startY, startZ)?.let {
      lastError = it
      return false
    }
    validateEtherwarpLanding("Goal block", goalX, goalY, goalZ)?.let {
      lastError = it
      return false
    }

    val currentId = searchId.incrementAndGet()
    val resolvedThreadCount = resolveEtherwarpThreadCount(threadCount)
    isSearching = true

    try {
      currentTask = Swift.executor.submit {
        try {
          val result = NativePathfinderBridge.findEtherwarpPath(
            NativePathfinderBridge.NativeEtherwarpSearchRequest(
              startX,
              startY,
              startZ,
              goalX,
              goalY,
              goalZ,
              maxIterations,
              resolvedThreadCount,
              yawStep,
              pitchStep,
              newNodeCost,
              heuristicWeight,
              rayLength,
              rewireEpsilon,
              resolvedEyeHeight
            )
          )

          if (searchId.get() != currentId) {
            return@submit
          }

          if (result != null && result.path.isNotEmpty()) {
            currentEtherwarpPath = EtherwarpSnapshot(
              points = toBlockPosList(result.path),
              angles = result.angles.copyOf(),
              timeMs = result.timeMs,
              nodesExplored = result.nodesExplored,
              nanosecondsPerNode = result.nanosecondsPerNode
            )
            lastError = null
          } else {
            currentEtherwarpPath = null
            lastError = NativePathfinderBridge.getLastError() ?: "No etherwarp path found to destination"
          }
        } catch (e: InterruptedException) {
          if (searchId.get() == currentId) {
            lastError = "Pathfinding was cancelled"
          }
          Thread.currentThread().interrupt()
        } catch (e: Exception) {
          if (searchId.get() == currentId) {
            lastError = e.message ?: "Unknown error during native etherwarp pathfinding"
            e.printStackTrace()
          }
        } finally {
          if (searchId.get() == currentId) {
            isSearching = false
            currentTask = null
          }
        }
      }
    } catch (e: Exception) {
      if (searchId.get() == currentId) {
        isSearching = false
        lastError = e.message ?: "Failed to submit native etherwarp pathfinding task"
      }
      return false
    }

    return true
  }

  private fun flattenPoints(points: Array<IntArray>): IntArray {
    val out = IntArray(points.size * 3)
    var idx = 0
    for (point in points) {
      out[idx++] = point[0]
      out[idx++] = point[1]
      out[idx++] = point[2]
    }
    return out
  }

  private fun toBlockPosList(flatPath: IntArray): List<BlockPos> {
    if (flatPath.isEmpty() || flatPath.size % 3 != 0) return emptyList()

    val out = ArrayList<BlockPos>(flatPath.size / 3)
    var idx = 0
    while (idx + 2 < flatPath.size) {
      out.add(BlockPos(flatPath[idx], flatPath[idx + 1], flatPath[idx + 2]))
      idx += 3
    }
    return out
  }

  private fun encodeAvoidZones(zones: Array<NativeAvoidZone>): Pair<IntArray, DoubleArray> {
    if (zones.isEmpty()) {
      return IntArray(0) to DoubleArray(0)
    }

    val meta = IntArray(zones.size * 5)
    val penalties = DoubleArray(zones.size)

    var idx = 0
    for (i in zones.indices) {
      val zone = zones[i]
      meta[idx++] = zone.x
      meta[idx++] = zone.y
      meta[idx++] = zone.z
      meta[idx++] = zone.radiusSq
      meta[idx++] = zone.maxYDiff
      penalties[i] = zone.penalty
    }

    return meta to penalties
  }

  private fun validateNativeAvailability(): String? {
    return if (NativePathfinderBridge.isAvailable()) {
      null
    } else {
      NativePathfinderBridge.getLastError() ?: "Native pathfinder unavailable"
    }
  }

  private fun validatePoints(label: String, points: Array<IntArray>): String? {
    if (points.isEmpty()) {
      return "$label are required"
    }
    if (points.any { it.size != 3 }) {
      return "$label must contain [x, y, z] points"
    }
    return null
  }

  private fun validateHeights(
    startPoints: Array<IntArray>,
    endPoints: Array<IntArray>,
    isFly: Boolean
  ): String? {
    val world = MinecraftClient.getInstance().world ?: return "World is not loaded"

    if (isFly) {
      val minY = world.bottomY
      val maxY = world.topYInclusive - 1

      if (startPoints.any { it[1] !in minY..maxY }) {
        return "Fly start Y must be between $minY and $maxY"
      }
      if (endPoints.any { it[1] !in minY..maxY }) {
        return "Fly end Y must be between $minY and $maxY"
      }
      return null
    }

    val minFeetY = world.bottomY + 1
    val maxFeetY = world.topYInclusive - 1
    if (startPoints.any { it[1] !in minFeetY..maxFeetY }) {
      return "Walk start Y must be between $minFeetY and $maxFeetY"
    }
    if (endPoints.any { it[1] !in minFeetY..maxFeetY }) {
      return "Walk end Y must be between $minFeetY and $maxFeetY"
    }
    return null
  }

  private fun unpackFlatPoints(label: String, points: IntArray): Array<IntArray>? {
    if (points.isEmpty()) {
      lastError = "$label are required"
      return null
    }
    if (points.size % 3 != 0) {
      lastError = "$label must be x,y,z triples"
      return null
    }

    val result = Array(points.size / 3) { IntArray(3) }
    var idx = 0
    while (idx < points.size) {
      val outIdx = idx / 3
      result[outIdx][0] = points[idx]
      result[outIdx][1] = points[idx + 1]
      result[outIdx][2] = points[idx + 2]
      idx += 3
    }

    return result
  }

  private fun validateEtherwarpLanding(label: String, x: Int, y: Int, z: Int): String? {
    val world = MinecraftClient.getInstance().world ?: return "World is not loaded"

    val supportFlags = NativeStateEncoder.flagsForState(world.getBlockState(BlockPos(x, y, z)))
    val feetFlags = NativeStateEncoder.flagsForState(world.getBlockState(BlockPos(x, y + 1, z)))
    val headFlags = NativeStateEncoder.flagsForState(world.getBlockState(BlockPos(x, y + 2, z)))

    if (!isEtherwarpStandable(supportFlags)) {
      return "$label must be a solid etherwarp landing block"
    }
    if (!isEtherwarpPassable(feetFlags)) {
      return "$label must have passable space above it"
    }
    if (!isEtherwarpPassable(headFlags)) {
      return "$label must have two passable blocks above it"
    }

    return null
  }

  private fun isEtherwarpStandable(flags: Int): Boolean {
    return (flags and NativeVoxelFlags.SOLID) != 0
  }

  private fun isEtherwarpPassable(flags: Int): Boolean {
    return (flags and NativeVoxelFlags.ETHER_PASSABLE) != 0
  }

  @JvmStatic
  fun getCurrentEtherwarpEyeHeight(): Double {
    return ETHERWARP_STANDING_EYE_HEIGHT - getCurrentEtherwarpSneakOffset()
  }

  @JvmStatic
  fun getCurrentEtherwarpSneakOffset(): Double {
    return if (isModernEtherwarpArea(getCurrentHypixelArea())) {
      ETHERWARP_MODERN_SNEAK_OFFSET
    } else {
      ETHERWARP_LEGACY_SNEAK_OFFSET
    }
  }

  private fun getCurrentHypixelArea(): String? {
    for (entry in TabList.getNames()) {
      val cleanLine = ChatLib.removeFormatting(entry.toString()).trim()
      if (!cleanLine.contains("Area:")) {
        continue
      }

      val value = cleanLine.substringAfter("Area:", "").trim()
      if (value.isNotEmpty()) {
        return value
      }
    }

    return null
  }

  private fun isModernEtherwarpArea(area: String?): Boolean {
    return area != null && area in MODERN_ETHERWARP_AREAS
  }

  private fun consumeTransientAvoidZones(): Array<NativeAvoidZone> {
    synchronized(avoidLock) {
      if (transientAvoidEntries.isEmpty()) return emptyArray()

      val zones = ArrayList<NativeAvoidZone>(transientAvoidEntries.size)
      transientAvoidEntries.removeIf { it.ttlSearches <= 0 }

      for (entry in transientAvoidEntries) {
        val radius = entry.radius.coerceAtLeast(1)
        zones.add(
          NativeAvoidZone(
            x = entry.x,
            y = entry.y,
            z = entry.z,
            radiusSq = radius * radius,
            penalty = entry.penalty.coerceAtLeast(0.0),
            maxYDiff = 2
          )
        )
        entry.ttlSearches--
      }

      transientAvoidEntries.removeIf { it.ttlSearches <= 0 }
      return zones.toTypedArray()
    }
  }

  @JvmStatic
  fun isSearching(): Boolean = isSearching

  @JvmStatic
  fun cancelSearch() {
    currentTask?.cancel(true)
    currentTask = null
    searchId.incrementAndGet()
    isSearching = false
    NativePathfinderBridge.cancelSearch()
  }

  @JvmStatic
  fun getPathArray(): IntArray {
    val snapshot = currentPath ?: return IntArray(0)
    val points = snapshot.points
    val result = IntArray(points.size * 3)
    val yOffset = if (snapshot.isFly) 0 else -1
    var idx = 0
    for (point in points) {
      result[idx++] = point.x
      result[idx++] = point.y + yOffset
      result[idx++] = point.z
    }
    return result
  }

  @JvmStatic
  fun getKeyNodesArray(): IntArray {
    val snapshot = currentPath ?: return IntArray(0)
    val keyNodes = snapshot.keyNodes
    val result = IntArray(keyNodes.size * 3)
    val yOffset = if (snapshot.isFly) 0 else -1
    var idx = 0
    for (point in keyNodes) {
      result[idx++] = point.x
      result[idx++] = point.y + yOffset
      result[idx++] = point.z
    }
    return result
  }

  @JvmStatic
  fun getEtherwarpPathArray(): IntArray {
    val snapshot = currentEtherwarpPath ?: return IntArray(0)
    val points = snapshot.points
    val result = IntArray(points.size * 3)
    var idx = 0
    for (point in points) {
      result[idx++] = point.x
      result[idx++] = point.y
      result[idx++] = point.z
    }
    return result
  }

  @JvmStatic
  fun getEtherwarpAnglesArray(): FloatArray = currentEtherwarpPath?.angles?.copyOf() ?: FloatArray(0)

  @JvmStatic
  fun getPathFlagsArray(): IntArray = currentAnnotations?.pathFlags ?: IntArray(0)

  @JvmStatic
  fun getKeyNodeFlagsArray(): IntArray = currentAnnotations?.keyNodeFlags ?: IntArray(0)

  @JvmStatic
  fun getKeyNodeMetricsArray(): IntArray = currentAnnotations?.keyNodeMetrics ?: IntArray(0)

  @JvmStatic
  fun getPathSignature(): String = currentAnnotations?.signatureHex ?: ""

  @JvmStatic
  fun getPathFlagBits(): IntArray = intArrayOf(
    FLAG_FLUID_FEET,
    FLAG_FLUID_HEAD,
    FLAG_LOW_HEADROOM,
    FLAG_NEAR_EDGE,
    FLAG_NEAR_WALL,
    FLAG_STEP_UP_NEXT,
    FLAG_DROP_NEXT,
    FLAG_TIGHT_CORRIDOR
  )

  @JvmStatic
  @JvmOverloads
  fun addTransientAvoidPoint(x: Int, y: Int, z: Int, radius: Int = 2, penalty: Double = 36.0, ttlSearches: Int = 2) {
    val clampedRadius = radius.coerceIn(1, 6)
    val clampedPenalty = penalty.coerceIn(5.0, 120.0)
    val clampedTtl = ttlSearches.coerceIn(1, 8)

    synchronized(avoidLock) {
      val existing = transientAvoidEntries.find { it.x == x && it.y == y && it.z == z && it.radius == clampedRadius }
      if (existing != null) {
        existing.ttlSearches = max(existing.ttlSearches, clampedTtl)
        existing.penalty = max(existing.penalty, clampedPenalty)
      } else {
        transientAvoidEntries.add(ManagedAvoidEntry(x, y, z, clampedRadius, clampedPenalty, clampedTtl))
      }
    }
  }

  @JvmStatic
  @JvmOverloads
  fun addTransientAvoidPoints(points: IntArray, radius: Int = 2, penalty: Double = 36.0, ttlSearches: Int = 2) {
    if (points.isEmpty() || points.size % 3 != 0) return
    var i = 0
    while (i < points.size) {
      addTransientAvoidPoint(points[i], points[i + 1], points[i + 2], radius, penalty, ttlSearches)
      i += 3
    }
  }

  @JvmStatic
  fun clearTransientAvoidPoints() {
    synchronized(avoidLock) {
      transientAvoidEntries.clear()
    }
  }

  @JvmStatic
  fun getPathSize(): Int = currentPath?.points?.size ?: 0

  @JvmStatic
  fun getEtherwarpPathSize(): Int = currentEtherwarpPath?.points?.size ?: 0

  @JvmStatic
  fun getKeyNodeCount(): Int = currentPath?.keyNodes?.size ?: 0

  @JvmStatic
  fun getLastTimeMs(): Long = currentPath?.timeMs ?: -1L

  @JvmStatic
  fun getEtherwarpLastTimeMs(): Long = currentEtherwarpPath?.timeMs ?: -1L

  @JvmStatic
  fun getNodesExplored(): Int = currentPath?.nodesExplored ?: 0

  @JvmStatic
  fun getEtherwarpNodesExplored(): Int = currentEtherwarpPath?.nodesExplored ?: 0

  @JvmStatic
  fun getNanosecondsPerNode(): Double = currentPath?.nanosecondsPerNode ?: 0.0

  @JvmStatic
  fun getEtherwarpNanosecondsPerNode(): Double = currentEtherwarpPath?.nanosecondsPerNode ?: 0.0

  @JvmStatic
  fun getSelectedStartIndex(): Int = currentPath?.selectedStartIndex ?: -1

  @JvmStatic
  fun setSearchVariantSeed(seed: Int) {
    searchVariantSeed = seed
  }

  @JvmStatic
  fun getLastError(): String? = lastError

  @JvmStatic
  fun hasPath(): Boolean = currentPath != null

  @JvmStatic
  fun hasEtherwarpPath(): Boolean = currentEtherwarpPath != null

  @JvmStatic
  fun clear() {
    cancelSearch()
    currentPath = null
    currentAnnotations = null
    currentEtherwarpPath = null
    lastError = null
  }
}
