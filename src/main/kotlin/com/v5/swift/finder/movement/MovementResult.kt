package com.v5.swift.finder.movement

class MovementResult {

  var x: Int = 0
  var y: Int = 0
  var z: Int = 0
  var cost: Double = Double.POSITIVE_INFINITY

  fun set(x: Int, y: Int, z: Int) {
    this.x = x
    this.y = y
    this.z = z
  }

  fun reset() {
    x = 0
    y = 0
    z = 0
    cost = Double.POSITIVE_INFINITY
  }

}
