package dev.quiteboring.swift.finder.movement

import dev.quiteboring.swift.finder.movement.movements.MovementFly

enum class MovesFly(val dx: Int, val dy: Int, val dz: Int) : IMove {

  NORTH(0, 0, -1),
  SOUTH(0, 0, 1),
  EAST(1, 0, 0),
  WEST(-1, 0, 0),
  NORTH_EAST(1, 0, -1),
  NORTH_WEST(-1, 0, -1),
  SOUTH_EAST(1, 0, 1),
  SOUTH_WEST(-1, 0, 1),

  UP(0, 1, 0),
  UP_NORTH(0, 1, -1),
  UP_SOUTH(0, 1, 1),
  UP_EAST(1, 1, 0),
  UP_WEST(-1, 1, 0),
  UP_NORTH_EAST(1, 1, -1),
  UP_NORTH_WEST(-1, 1, -1),
  UP_SOUTH_EAST(1, 1, 1),
  UP_SOUTH_WEST(-1, 1, 1),

  DOWN(0, -1, 0),
  DOWN_NORTH(0, -1, -1),
  DOWN_SOUTH(0, -1, 1),
  DOWN_EAST(1, -1, 0),
  DOWN_WEST(-1, -1, 0),
  DOWN_NORTH_EAST(1, -1, -1),
  DOWN_NORTH_WEST(-1, -1, -1),
  DOWN_SOUTH_EAST(1, -1, 1),
  DOWN_SOUTH_WEST(-1, -1, 1);

  override fun calculate(ctx: CalculationContext, px: Int, py: Int, pz: Int, res: MovementResult) {
    MovementFly.calculate(ctx, px, py, pz, dx, dy, dz, res)
  }

}
