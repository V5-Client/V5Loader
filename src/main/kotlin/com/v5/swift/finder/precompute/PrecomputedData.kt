package com.v5.swift.finder.precompute

import com.v5.swift.finder.helper.BlockStateAccessor
import com.v5.swift.finder.movement.MovementHelper
import net.minecraft.block.*
import net.minecraft.block.enums.SlabType

class PrecomputedData(private val bsa: BlockStateAccessor) {

  @JvmField
  val states = IntArray(Block.STATE_IDS.size())

  companion object {
    const val COMPUTED = 1 shl 0
    const val SOLID = 1 shl 1
    const val PASSABLE = 1 shl 2
    const val FULL_CUBE = 1 shl 3
    const val SLAB_BOTTOM = 1 shl 4
    const val CARPET_LIKE = 1 shl 5
    const val BLOCKING_WALL = 1 shl 6
    const val FLY_PASSABLE = 1 shl 7
  }

  private fun compute(id: Int, state: BlockState, x: Int, y: Int, z: Int): Int {
    if (state.isAir) {
      val data = COMPUTED or PASSABLE
      states[id] = data
      return data
    }

    var data = COMPUTED
    val block = state.block

    if (block is CarpetBlock) {
      data = data or PASSABLE or CARPET_LIKE
      states[id] = data
      return data
    }

    val isPassThrough = block is SlabBlock || block is StairsBlock ||
      block is DoorBlock || block is TrapdoorBlock || block is TorchBlock ||
      block is SignBlock || block is WallSignBlock || block is PlantBlock ||
      block is AbstractRailBlock || block is VineBlock || block is LadderBlock ||
      block is SnowBlock || block is PressurePlateBlock || block is ButtonBlock ||
      block is RedstoneWireBlock || block is LeverBlock || block is BannerBlock ||
      block is WallBannerBlock || block is TripwireBlock || block is TripwireHookBlock

    val isFlyPassable = block is LadderBlock || block is VineBlock ||
      block is AbstractRailBlock || block is SignBlock || block is WallSignBlock ||
      block is BannerBlock || block is WallBannerBlock || block is TripwireBlock ||
      block is TripwireHookBlock || block is LeverBlock || block is ButtonBlock ||
      block is TorchBlock || block is RedstoneWireBlock || block is PressurePlateBlock

    if (isFlyPassable) {
      data = data or FLY_PASSABLE
    }

    val isFenceLike = block is FenceBlock || block is FenceGateBlock || block is WallBlock
    if (isFenceLike) {
      data = data or SOLID or BLOCKING_WALL
    }

    when (block) {
      is SlabBlock -> {
        val slabType = state.get(SlabBlock.TYPE)!!
        data = data or SOLID or when (slabType) {
          SlabType.BOTTOM -> SLAB_BOTTOM
          SlabType.TOP -> FULL_CUBE or BLOCKING_WALL
          SlabType.DOUBLE -> FULL_CUBE or BLOCKING_WALL
        }
      }

      is StairsBlock -> {
        data = data or SOLID
      }

      else -> {
        if (!isFenceLike) {
          bsa.mutablePos.set(x, y, z)
          if (state.isFullCube(bsa.access, bsa.mutablePos)) {
            data = data or SOLID or FULL_CUBE
            if (!isPassThrough) {
              data = data or BLOCKING_WALL
            }
          } else {
            val shape = state.getCollisionShape(bsa.access, bsa.mutablePos, MovementHelper.SHAPE_CONTEXT)
            if (!shape.isEmpty) {
              data = data or SOLID
              val box = shape.boundingBox
              if (box.maxY - box.minY >= 0.5 && !isPassThrough) {
                data = data or BLOCKING_WALL
              }
            } else {
              data = data or PASSABLE
            }
          }
        }
      }
    }

    states[id] = data
    return data
  }

  fun getData(x: Int, y: Int, z: Int, state: BlockState): Int {
    val id = Block.STATE_IDS.getRawId(state)
    val cached = states[id]
    if ((cached and COMPUTED) != 0) return cached
    return compute(id, state, x, y, z)
  }

  fun isSolid(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    !state.isAir && (getData(x, y, z, state) and SOLID) != 0

  fun isPassable(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    state.isAir || (getData(x, y, z, state) and PASSABLE) != 0

  fun isPassableForFlying(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    state.isAir || (getData(x, y, z, state) and (PASSABLE or FLY_PASSABLE or CARPET_LIKE)) != 0

  fun isFullCube(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    !state.isAir && (getData(x, y, z, state) and FULL_CUBE) != 0

  fun isBottomSlab(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    (getData(x, y, z, state) and SLAB_BOTTOM) != 0

  fun isBlockingWall(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    !state.isAir && (getData(x, y, z, state) and BLOCKING_WALL) != 0

  fun isCarpetLike(x: Int, y: Int, z: Int, state: BlockState = bsa.get(x, y, z)): Boolean =
    (getData(x, y, z, state) and CARPET_LIKE) != 0

}
