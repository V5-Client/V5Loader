package com.v5.swift.nativepath

import net.minecraft.block.AbstractRailBlock
import net.minecraft.block.AbstractSkullBlock
import net.minecraft.block.BannerBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.ButtonBlock
import net.minecraft.block.CarpetBlock
import net.minecraft.block.ComparatorBlock
import net.minecraft.block.DoorBlock
import net.minecraft.block.FenceBlock
import net.minecraft.block.FenceGateBlock
import net.minecraft.block.FlowerPotBlock
import net.minecraft.block.LadderBlock
import net.minecraft.block.LeverBlock
import net.minecraft.block.PlantBlock
import net.minecraft.block.PressurePlateBlock
import net.minecraft.block.RedstoneWireBlock
import net.minecraft.block.SignBlock
import net.minecraft.block.SlabBlock
import net.minecraft.block.SnowBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.TorchBlock
import net.minecraft.block.TrapdoorBlock
import net.minecraft.block.TripwireBlock
import net.minecraft.block.TripwireHookBlock
import net.minecraft.block.VineBlock
import net.minecraft.block.WallBannerBlock
import net.minecraft.block.WallBlock
import net.minecraft.block.WallSignBlock
import net.minecraft.block.enums.BlockHalf
import net.minecraft.block.enums.SlabType
import net.minecraft.util.math.BlockPos
import net.minecraft.world.EmptyBlockView
import net.minecraft.block.ShapeContext

object NativeStateEncoder {
  private const val DEFAULT_EMPTY_FLAGS =
    NativeVoxelFlags.PASSABLE or
      NativeVoxelFlags.PASSABLE_FLY or
      NativeVoxelFlags.ETHER_PASSABLE or
      NativeVoxelFlags.ETHER_TELEPORT_CLEAR

  private val ORIGIN: BlockPos = BlockPos.ORIGIN
  private val EMPTY_VIEW = EmptyBlockView.INSTANCE
  private val SHAPE_CONTEXT: ShapeContext = ShapeContext.absent()

  private val stateFlags = IntArray(Block.STATE_IDS.size()) { Int.MIN_VALUE }

  @JvmStatic
  fun flagsForStateId(stateId: Int): Int {
    if (stateId < 0 || stateId >= stateFlags.size) {
      return DEFAULT_EMPTY_FLAGS
    }

    val cached = stateFlags[stateId]
    if (cached != Int.MIN_VALUE) {
      return cached
    }

    val state = Block.STATE_IDS.get(stateId)
      ?: return DEFAULT_EMPTY_FLAGS

    val flags = computeFlags(state)
    stateFlags[stateId] = flags
    return flags
  }

  @JvmStatic
  fun flagsForState(state: BlockState): Int {
    val stateId = Block.STATE_IDS.getRawId(state)
    return flagsForStateId(stateId)
  }

  @JvmStatic
  fun flagsShortForState(state: BlockState): Short = flagsForState(state).toShort()

  @JvmStatic
  fun flagsShortForStateId(stateId: Int): Short = flagsForStateId(stateId).toShort()

  private fun computeFlags(state: BlockState): Int {
    if (state.isAir) {
      return DEFAULT_EMPTY_FLAGS
    }

    var flags = 0
    val block = state.block
    val collisionShape = state.getCollisionShape(EMPTY_VIEW, ORIGIN, SHAPE_CONTEXT)

    if (!state.fluidState.isEmpty) {
      flags = flags or NativeVoxelFlags.FLUID
    }

    if (block is CarpetBlock) {
      return flags or NativeVoxelFlags.PASSABLE or NativeVoxelFlags.PASSABLE_FLY or NativeVoxelFlags.CARPET_LIKE
    }

    // Heads/skulls should not obstruct pathing or etherwarp landing clearance checks.
    if (block is AbstractSkullBlock) {
      return flags or
        NativeVoxelFlags.PASSABLE or
        NativeVoxelFlags.PASSABLE_FLY or
        NativeVoxelFlags.ETHER_PASSABLE or
        NativeVoxelFlags.ETHER_FEET_BLOCKER
    }

    val isPassThrough = block is SlabBlock ||
      block is StairsBlock ||
      block is DoorBlock ||
      block is TrapdoorBlock ||
      block is TorchBlock ||
      block is SignBlock ||
      block is WallSignBlock ||
      block is PlantBlock ||
      block is AbstractRailBlock ||
      block is VineBlock ||
      block is LadderBlock ||
      block is SnowBlock ||
      block is PressurePlateBlock ||
      block is ButtonBlock ||
      block is RedstoneWireBlock ||
      block is LeverBlock ||
      block is BannerBlock ||
      block is WallBannerBlock ||
      block is TripwireBlock ||
      block is TripwireHookBlock

    val isFlyPassable = block is LadderBlock ||
      block is VineBlock ||
      block is AbstractRailBlock ||
      block is SignBlock ||
      block is WallSignBlock ||
      block is BannerBlock ||
      block is WallBannerBlock ||
      block is TripwireBlock ||
      block is TripwireHookBlock ||
      block is LeverBlock ||
      block is ButtonBlock ||
      block is TorchBlock ||
      block is RedstoneWireBlock ||
      block is PressurePlateBlock

    if (isFlyPassable) {
      flags = flags or NativeVoxelFlags.PASSABLE_FLY
    }

    if (block is FenceBlock || block is FenceGateBlock || block is WallBlock) {
      flags = flags or NativeVoxelFlags.SOLID or NativeVoxelFlags.BLOCKING_WALL or NativeVoxelFlags.FENCE_LIKE
    }

    when (block) {
      is SlabBlock -> {
        flags = flags or NativeVoxelFlags.SOLID
        when (state.get(SlabBlock.TYPE) ?: SlabType.BOTTOM) {
          SlabType.BOTTOM -> flags = flags or NativeVoxelFlags.SLAB_BOTTOM
          SlabType.TOP -> flags = flags or NativeVoxelFlags.SLAB_TOP or NativeVoxelFlags.BLOCKING_WALL
          SlabType.DOUBLE -> flags = flags or NativeVoxelFlags.BLOCKING_WALL
        }
      }

      is StairsBlock -> {
        flags = flags or NativeVoxelFlags.SOLID
        if (state.get(StairsBlock.HALF) == BlockHalf.BOTTOM) {
          flags = flags or NativeVoxelFlags.STAIRS_BOTTOM
        }
      }

      else -> {
        if (collisionShape.isEmpty) {
          flags = flags or NativeVoxelFlags.PASSABLE
        } else {
          flags = flags or NativeVoxelFlags.SOLID
          val box = collisionShape.boundingBox
          if (box.maxY - box.minY >= 0.5 && !isPassThrough) {
            flags = flags or NativeVoxelFlags.BLOCKING_WALL
          }
        }
      }
    }

    if ((flags and NativeVoxelFlags.PASSABLE) != 0 || (flags and NativeVoxelFlags.CARPET_LIKE) != 0) {
      flags = flags or NativeVoxelFlags.PASSABLE_FLY
    }

    val etherPassable = when {
      block is ComparatorBlock -> true
      block is FlowerPotBlock -> true
      block is LadderBlock -> true
      block is SignBlock || block is WallSignBlock -> false
      else -> collisionShape.isEmpty
    }
    val etherwarpFeetBlocker = when (block) {
      is ComparatorBlock,
      is FlowerPotBlock,
      is LadderBlock,
      is VineBlock -> true
      else -> false
    }
    // Signs are special: Etherwarp allows the landing space to be considered clear even though
    // the block is not ray-passable, while small collision blocks still prevent the player body
    // from occupying that space after teleporting.
    val teleportSpaceClear =
      (etherPassable || block is SignBlock || block is WallSignBlock) && !etherwarpFeetBlocker

    if (etherPassable) {
      flags = flags or NativeVoxelFlags.ETHER_PASSABLE
    }
    if (teleportSpaceClear) {
      flags = flags or NativeVoxelFlags.ETHER_TELEPORT_CLEAR
    }
    if (etherwarpFeetBlocker) {
      flags = flags or NativeVoxelFlags.ETHER_FEET_BLOCKER
    }

    return flags
  }
}
