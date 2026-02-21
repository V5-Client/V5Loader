package com.v5.swift.finder.movement

import com.v5.swift.finder.helper.BlockStateAccessor
import net.minecraft.block.BlockState
import net.minecraft.block.CarpetBlock
import net.minecraft.block.ShapeContext

object MovementHelper {

  @JvmField
  val SHAPE_CONTEXT: ShapeContext = ShapeContext.absent()

  @JvmStatic
  fun isSolidState(bsa: BlockStateAccessor, x: Int, y: Int, z: Int, state: BlockState): Boolean {
    if (state.isAir || state.block is CarpetBlock) return false
    bsa.mutablePos.set(x, y, z)
    return state.isFullCube(bsa.access, bsa.mutablePos) ||
      !state.getCollisionShape(bsa.access, bsa.mutablePos, SHAPE_CONTEXT).isEmpty
  }

  @JvmStatic
  fun isPassableState(bsa: BlockStateAccessor, x: Int, y: Int, z: Int, state: BlockState): Boolean {
    if (state.isAir || state.block is CarpetBlock) return true
    bsa.mutablePos.set(x, y, z)
    return state.getCollisionShape(bsa.access, bsa.mutablePos, SHAPE_CONTEXT).isEmpty
  }

}
