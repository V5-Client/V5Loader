package dev.quiteboring.swift.finder.goal

interface IGoal {

  fun isAtGoal(x: Int, y: Int, z: Int): Boolean
  fun heuristic(x: Int, y: Int, z: Int): Double

}
