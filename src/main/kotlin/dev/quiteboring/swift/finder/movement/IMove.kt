package dev.quiteboring.swift.finder.movement

interface IMove {

  fun calculate(
    ctx: CalculationContext,
    px: Int,
    py: Int,
    pz: Int,
    res: MovementResult
  )

}
