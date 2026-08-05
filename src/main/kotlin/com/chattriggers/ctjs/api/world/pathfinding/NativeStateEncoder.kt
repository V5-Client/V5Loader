package com.chattriggers.ctjs.api.world.pathfinding

import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.StandingSignBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TorchBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.phys.shapes.CollisionContext

object NativeStateEncoder {
  private const val DEFAULT_EMPTY_FLAGS =
    NativeVoxelFlags.PASSABLE or
      NativeVoxelFlags.PASSABLE_FLY or
      NativeVoxelFlags.ETHER_PASSABLE or
      NativeVoxelFlags.ETHER_TELEPORT_CLEAR

  private val ORIGIN: BlockPos = BlockPos.ZERO
  private val EMPTY_VIEW = EmptyBlockGetter.INSTANCE
  private val SHAPE_CONTEXT: CollisionContext = CollisionContext.empty()

  private val stateFlags = IntArray(Block.BLOCK_STATE_REGISTRY.size()) { Int.MIN_VALUE }

  @JvmStatic
  fun flagsForStateId(stateId: Int): Int {
    if (stateId < 0 || stateId >= stateFlags.size) {
      return DEFAULT_EMPTY_FLAGS
    }

    val cached = stateFlags[stateId]
    if (cached != Int.MIN_VALUE) {
      return cached
    }

    val state = Block.BLOCK_STATE_REGISTRY.byId(stateId)
      ?: return DEFAULT_EMPTY_FLAGS

    val flags = computeFlags(state)
    stateFlags[stateId] = flags
    return flags
  }

  @JvmStatic
  fun flagsForState(state: BlockState): Int {
    val stateId = Block.BLOCK_STATE_REGISTRY.getId(state)
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
      block is StairBlock ||
      block is DoorBlock ||
      block is TrapDoorBlock ||
      block is TorchBlock ||
      block is StandingSignBlock ||
      block is WallSignBlock ||
      block is VegetationBlock ||
      block is BaseRailBlock ||
      block is VineBlock ||
      block is LadderBlock ||
      block is SnowLayerBlock ||
      block is PressurePlateBlock ||
      block is ButtonBlock ||
      block is RedStoneWireBlock ||
      block is LeverBlock ||
      block is BannerBlock ||
      block is WallBannerBlock ||
      block is TripWireBlock ||
      block is TripWireHookBlock

    val isFlyPassable = block is LadderBlock ||
      block is VineBlock ||
      block is BaseRailBlock ||
      block is StandingSignBlock ||
      block is WallSignBlock ||
      block is BannerBlock ||
      block is WallBannerBlock ||
      block is TripWireBlock ||
      block is TripWireHookBlock ||
      block is LeverBlock ||
      block is ButtonBlock ||
      block is TorchBlock ||
      block is RedStoneWireBlock ||
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
        when (state.getValue(SlabBlock.TYPE)) {
          SlabType.BOTTOM -> flags = flags or NativeVoxelFlags.SLAB_BOTTOM
          SlabType.TOP -> flags = flags or NativeVoxelFlags.SLAB_TOP or NativeVoxelFlags.BLOCKING_WALL
          SlabType.DOUBLE -> flags = flags or NativeVoxelFlags.BLOCKING_WALL
        }
      }

      is StairBlock -> {
        flags = flags or NativeVoxelFlags.SOLID
        if (state.getValue(StairBlock.HALF) == Half.BOTTOM) {
          flags = flags or NativeVoxelFlags.STAIRS_BOTTOM
        }
      }

      else -> {
        if (collisionShape.isEmpty) {
          flags = flags or NativeVoxelFlags.PASSABLE
        } else {
          flags = flags or NativeVoxelFlags.SOLID
          val box = collisionShape.bounds()
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
      block is StandingSignBlock || block is WallSignBlock -> false
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
      (etherPassable || block is StandingSignBlock || block is WallSignBlock) && !etherwarpFeetBlocker

    if (etherPassable) {
      flags = flags or NativeVoxelFlags.ETHER_PASSABLE
    }
    if (teleportSpaceClear) {
      flags = flags or NativeVoxelFlags.ETHER_TELEPORT_CLEAR
    }
    if (etherwarpFeetBlocker) {
      flags = flags or NativeVoxelFlags.ETHER_FEET_BLOCKER
    }

    if (block is SnowLayerBlock) {
      flags = flags or NativeVoxelFlags.ETHER_FAKE_FULL_BLOCKER
    }

    return flags
  }
}
