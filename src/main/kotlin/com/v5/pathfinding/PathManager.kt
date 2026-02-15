package com.v5.pathfinding

import dev.quiteboring.swift.Swift
import dev.quiteboring.swift.finder.calculate.Path
import dev.quiteboring.swift.finder.calculate.path.AStarPathfinder
import dev.quiteboring.swift.finder.goal.Goal
import dev.quiteboring.swift.finder.goal.GoalFly
import dev.quiteboring.swift.finder.goal.IGoal
import dev.quiteboring.swift.finder.goal.MultiGoal
import dev.quiteboring.swift.finder.movement.CalculationContext
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

object PathManager {
    private const val NON_PRIMARY_START_PENALTY = 250.0

    @Volatile
    private var currentPath: Path? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var isSearching: Boolean = false

    private var currentTask: Future<*>? = null
    private val searchId = AtomicInteger(0)

    @JvmStatic
    @JvmOverloads
    fun findPath(startX: Int, startY: Int, startZ: Int, endX: Int, endY: Int, endZ: Int, maxIterations: Int = 500_000, isFly: Boolean = false
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
    fun findPathMultipleGoals(startX: Int, startY: Int, startZ: Int, endGoals: IntArray, maxIterations: Int = 500_000, isFly: Boolean = false
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
        } catch (e: Exception) {
            lastError = e.message ?: "Failed to create calculation context"
            e.printStackTrace()
            return false
        }

        val goal = createGoal(startPoints, endPoints, ctx, isFly)

        val currentId = searchId.incrementAndGet()
        isSearching = true

        currentTask = Swift.executor.submit {
            try {
                val pathfinder = AStarPathfinder(
                    startPoints = startPoints,
                    goal = goal,
                    ctx = ctx,
                    maxIterations = maxIterations,
                    nonPrimaryStartPenalty = if (isFly) 0.0 else NON_PRIMARY_START_PENALTY,
                    isFly = isFly
                )
                val path = pathfinder.findPath()

                if (searchId.get() == currentId) {
                    currentPath = path
                    if (path == null) {
                        lastError = "No path found to destination"
                    }
                }
            } catch (e: InterruptedException) {
                if (searchId.get() == currentId) {
                    lastError = "Pathfinding was cancelled"
                }
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
        var idx = 0
        for (point in points) {
            result[idx++] = point.x
            result[idx++] = point.y - 1
            result[idx++] = point.z
        }
        return result
    }

    @JvmStatic
    fun getKeyNodesArray(): IntArray {
        val path = currentPath ?: return IntArray(0)
        val keyNodes = path.keyNodes
        val result = IntArray(keyNodes.size * 3)
        var idx = 0
        for (point in keyNodes) {
            result[idx++] = point.x
            result[idx++] = point.y - 1
            result[idx++] = point.z
        }
        return result
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
    fun getLastError(): String? = lastError

    @JvmStatic
    fun hasPath(): Boolean = currentPath != null

    @JvmStatic
    fun clear() {
        cancelSearch()
        currentPath = null
        lastError = null
    }
}
