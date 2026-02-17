package dev.quiteboring.swift.finder.goal

class MultiGoal(
  private val goals: List<IGoal>
) : IGoal {

  override fun isAtGoal(x: Int, y: Int, z: Int): Boolean {
    for (goal in goals) {
      if (goal.isAtGoal(x, y, z)) {
        return true
      }
    }
    return false
  }

  override fun heuristic(x: Int, y: Int, z: Int): Double {
    var best = Double.POSITIVE_INFINITY
    for (goal in goals) {
      val h = goal.heuristic(x, y, z)
      if (h < best) best = h
    }
    return best
  }
}
