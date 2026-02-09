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
        return findPathMultipleGoals(
            startX,
            startY,
            startZ,
            intArrayOf(endX, endY, endZ),
            maxIterations,
            isFly
        )
    }

    @JvmStatic
    @JvmOverloads
    fun findPathMultipleGoals(startX: Int, startY: Int, startZ: Int, endGoals: IntArray, maxIterations: Int = 500_000, isFly: Boolean = false
    ): Boolean {
        cancelSearch()

        lastError = null
        currentPath = null

        val ctx: CalculationContext
        try {
            ctx = CalculationContext()
        } catch (e: Exception) {
            lastError = e.message ?: "Failed to create calculation context"
            e.printStackTrace()
            return false
        }

        val goals = createGoals(startX, startY, startZ, endGoals, ctx, isFly) ?: return false
        val goal = if (goals.size == 1) goals[0] else MultiGoal(goals)

        if (isFly && goal is MultiGoal && goals.isNotEmpty()) {
            val firstGoal = goals[0]
                if (firstGoal is GoalFly) {
                    ctx.setFlightParameters(
                    startX, startY, startZ,
                    firstGoal.goalX, firstGoal.goalY, firstGoal.goalZ
                )
            }
        }

        val currentId = searchId.incrementAndGet()
        isSearching = true

        currentTask = Swift.executor.submit {
            try {
                val pathfinder = AStarPathfinder(startX, startY, startZ, goal = goal, ctx = ctx, maxIterations = maxIterations, isFly = isFly)
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

    private fun createGoals(startX: Int, startY: Int, startZ: Int, endGoals: IntArray, ctx: CalculationContext, isFly: Boolean): List<IGoal>? {
        if (endGoals.isEmpty()) {
            lastError = "No end goals provided"
            return null
        }
        if (endGoals.size % 3 != 0) {
            lastError = "End goals must be provided as x,y,z triples"
            return null
        }

        val goals = ArrayList<IGoal>(endGoals.size / 3)
        var idx = 0
        while (idx < endGoals.size) {
            val goalX = endGoals[idx]
            val goalY = endGoals[idx + 1]
            val goalZ = endGoals[idx + 2]
            val goal = if (isFly) {
                GoalFly(startX, startY, startZ, goalX, goalY, goalZ, ctx)
            } else {
                Goal(startX, startZ, goalX, goalY, goalZ, ctx)
            }
            goals.add(goal)
            idx += 3
        }
        return goals
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