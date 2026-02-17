package dev.quiteboring.swift.finder.helper

import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.fluid.FluidState
import net.minecraft.util.math.BlockPos
import net.minecraft.world.BlockView

class BlockViewWrapper(
  private val bsa: BlockStateAccessor,
) : BlockView {

  override fun getBlockEntity(pos: BlockPos): BlockEntity? {
    return null
  }

  override fun getBlockState(pos: BlockPos): BlockState {
    return bsa.get(pos.x, pos.y, pos.z)
  }

  override fun getFluidState(pos: BlockPos): FluidState? {
    return getBlockState(pos).fluidState
  }

  override fun getHeight(): Int {
    return bsa.world.height
  }

  override fun getBottomY(): Int {
    return bsa.world.bottomY
  }

}
