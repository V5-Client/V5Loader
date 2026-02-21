package com.v5.swift.finder.movement

interface IMove {

  fun calculate(
    ctx: CalculationContext,
    px: Int,
    py: Int,
    pz: Int,
    res: MovementResult
  )

}
