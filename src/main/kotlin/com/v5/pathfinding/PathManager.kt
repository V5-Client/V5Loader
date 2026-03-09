package com.v5.pathfinding

import com.v5.swift.Swift
import com.v5.swift.finder.calculate.Path
import com.v5.swift.finder.calculate.path.AStarPathfinder
import com.v5.swift.finder.goal.Goal
import com.v5.swift.finder.goal.GoalFly
import com.v5.swift.finder.goal.IGoal
import com.v5.swift.finder.goal.MultiGoal
import com.v5.swift.finder.movement.CalculationContext
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import net.minecraft.util.math.BlockPos

object PathManager {
    private const val NON_PRIMARY_START_PENALTY = 250.0
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

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
        val penalty: Double,
        var ttlSearches: Int
    )

    @Volatile
    private var currentPath: Path? = null

    @Volatile
    private var currentAnnotations: PathAnnotations? = null

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

        val ctx: CalculationContext
        try {
            ctx = CalculationContext()
            ctx.setTransientAvoidZones(consumeTransientAvoidZones())
        } catch (e: Exception) {
            lastError = e.message ?: "Failed to create calculation context"
            e.printStackTrace()
            return false
        }

        val goal = createGoal(startPoints, endPoints, ctx, isFly)

        val currentId = searchId.incrementAndGet()
        isSearching = true

        try {
            currentTask = Swift.executor.submit {
                try {
                    val pathfinder = AStarPathfinder(
                        startPoints = startPoints,
                        goal = goal,
                        ctx = ctx,
                        maxIterations = maxIterations,
                        nonPrimaryStartPenalty = if (isFly) 0.0 else NON_PRIMARY_START_PENALTY,
                        isFly = isFly,
                        moveOrderOffset = if (isFly) 0 else searchVariantSeed
                    )
                    val path = pathfinder.findPath()

                    if (searchId.get() == currentId) {
                        currentPath = path
                        currentAnnotations = if (path != null) buildAnnotations(path, ctx) else null
                        if (path == null) {
                            lastError = "No path found to destination"
                        }
                    }
                } catch (e: InterruptedException) {
                    if (searchId.get() == currentId) {
                        lastError = "Pathfinding was cancelled"
                    }
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    if (searchId.get() == currentId) {
                        lastError = e.message ?: "Unknown error during pathfinding"
                        e.printStackTrace()
                    }
                } finally {
                    if (searchId.get() == currentId) {
                        isSearching = false
                    }
                }
            }
        } catch (e: Exception) {
            if (searchId.get() == currentId) {
                isSearching = false
                lastError = e.message ?: "Failed to submit pathfinding task"
            }
            return false
        }

        return true
    }

    private fun createGoal(startPoints: Array<IntArray>, endPoints: Array<IntArray>, ctx: CalculationContext, isFly: Boolean): IGoal {
        if (isFly) {
            val start = startPoints[0]
            val end = endPoints[0]
            return GoalFly(
                start[0], start[1], start[2],
                end[0], end[1], end[2],
                ctx
            )
        }

        val firstStart = startPoints[0]
        val heuristicStartX = firstStart[0]
        val heuristicStartZ = firstStart[2]

        val goals = ArrayList<IGoal>(endPoints.size)
        for (end in endPoints) {
            goals.add(
                Goal(
                    heuristicStartX,
                    heuristicStartZ,
                    end[0],
                    end[1],
                    end[2],
                    ctx
                )
            )
        }

        return if (goals.size == 1) goals[0] else MultiGoal(goals)
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

    private fun consumeTransientAvoidZones(): Array<CalculationContext.AvoidZone> {
        synchronized(avoidLock) {
            if (transientAvoidEntries.isEmpty()) return emptyArray()

            val zones = ArrayList<CalculationContext.AvoidZone>(transientAvoidEntries.size)
            transientAvoidEntries.removeIf { it.ttlSearches <= 0 }

            for (entry in transientAvoidEntries) {
                val radius = entry.radius.coerceAtLeast(1)
                zones.add(
                    CalculationContext.AvoidZone(
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

    private fun buildAnnotations(path: Path, ctx: CalculationContext): PathAnnotations {
        val pathFlags = encodeNodeFlags(path.points, ctx, path.isFlyPath, includeProximity = false)
        val keyFlags = encodeNodeFlags(path.keyNodes, ctx, path.isFlyPath, includeProximity = true)
        val keyMetrics = encodeKeyMetrics(path.keyNodes, ctx)
        val signatureHex = buildPathSignatureHex(path.points)
        return PathAnnotations(pathFlags, keyFlags, keyMetrics, signatureHex)
    }

    private fun encodeNodeFlags(
        nodes: List<BlockPos>,
        ctx: CalculationContext,
        isFly: Boolean,
        includeProximity: Boolean
    ): IntArray {
        if (nodes.isEmpty()) return IntArray(0)

        val out = IntArray(nodes.size)
        val pre = ctx.precomputedData
        val yOffset = if (isFly) 0 else -1
        val wdc = if (includeProximity) ctx.wdc else null

        for (i in nodes.indices) {
            val p = nodes[i]
            val yOut = p.y + yOffset
            val walkY = yOut + if (isFly) 0 else 1

            var flags = 0
            if (pre.isFluid(p.x, walkY, p.z, ctx.get(p.x, walkY, p.z))) flags = flags or FLAG_FLUID_FEET
            if (pre.isFluid(p.x, walkY + 1, p.z, ctx.get(p.x, walkY + 1, p.z))) flags = flags or FLAG_FLUID_HEAD

            if (!isFly) {
                if (
                    !pre.isPassable(p.x, walkY, p.z, ctx.get(p.x, walkY, p.z)) ||
                    !pre.isPassable(p.x, walkY + 1, p.z, ctx.get(p.x, walkY + 1, p.z))
                ) {
                    flags = flags or FLAG_LOW_HEADROOM
                }
            }

            if (wdc != null) {
                val edgeDist = wdc.getEdgeDistance(p.x, walkY, p.z)
                val wallDist = wdc.getWallDistance(p.x, walkY, p.z)
                if (edgeDist <= 1) flags = flags or FLAG_NEAR_EDGE
                if (wallDist <= 1) flags = flags or FLAG_NEAR_WALL
                if (edgeDist <= 1 && wallDist <= 1) flags = flags or FLAG_TIGHT_CORRIDOR
            }

            if (i + 1 < nodes.size) {
                val nextY = nodes[i + 1].y
                val dy = nextY - p.y
                if (dy > 0) flags = flags or FLAG_STEP_UP_NEXT
                if (dy < 0) flags = flags or FLAG_DROP_NEXT
            }

            out[i] = flags
        }

        return out
    }

    private fun encodeKeyMetrics(nodes: List<BlockPos>, ctx: CalculationContext): IntArray {
        if (nodes.isEmpty()) return IntArray(0)
        val result = IntArray(nodes.size * 3)
        val wdc = ctx.wdc
        var outIdx = 0
        for (i in nodes.indices) {
            val p = nodes[i]
            result[outIdx++] = wdc.getEdgeDistance(p.x, p.y, p.z)
            result[outIdx++] = wdc.getWallDistance(p.x, p.y, p.z)
            result[outIdx++] = if (i + 1 < nodes.size) nodes[i + 1].y - p.y else 0
        }
        return result
    }

    private fun buildPathSignatureHex(points: List<BlockPos>): String {
        var hash = FNV_OFFSET_BASIS
        for (p in points) {
            val pointHash = (p.x.toLong() * 73856093L) xor (p.y.toLong() * 19349663L) xor (p.z.toLong() * 83492791L)
            hash = (hash xor pointHash) * FNV_PRIME
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    @JvmStatic
    fun isSearching(): Boolean = isSearching

    @JvmStatic
    fun cancelSearch() {
        currentTask?.cancel(true)
        currentTask = null
        searchId.incrementAndGet()
        isSearching = false
    }

    @JvmStatic
    fun getPathArray(): IntArray {
        val path = currentPath ?: return IntArray(0)
        val points = path.points
        val result = IntArray(points.size * 3)
        val yOffset = if (path.isFlyPath) 0 else -1
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
        val path = currentPath ?: return IntArray(0)
        val keyNodes = path.keyNodes
        val result = IntArray(keyNodes.size * 3)
        val yOffset = if (path.isFlyPath) 0 else -1
        var idx = 0
        for (point in keyNodes) {
            result[idx++] = point.x
            result[idx++] = point.y + yOffset
            result[idx++] = point.z
        }
        return result
    }

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
    fun getKeyNodeCount(): Int = currentPath?.keyNodes?.size ?: 0

    @JvmStatic
    fun getLastTimeMs(): Long = currentPath?.timeTaken ?: -1L

    @JvmStatic
    fun getNodesExplored(): Int = currentPath?.nodesExplored ?: 0

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
    fun clear() {
        cancelSearch()
        currentPath = null
        currentAnnotations = null
        lastError = null
    }
}
