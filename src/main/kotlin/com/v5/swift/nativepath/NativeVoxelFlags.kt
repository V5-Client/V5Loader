package com.v5.swift.nativepath

object NativeVoxelFlags {
  const val PASSABLE = 1 shl 0
  const val SOLID = 1 shl 1
  const val PASSABLE_FLY = 1 shl 2
  const val BLOCKING_WALL = 1 shl 3
  const val FLUID = 1 shl 4
  const val SLAB_BOTTOM = 1 shl 5
  const val SLAB_TOP = 1 shl 6
  const val FENCE_LIKE = 1 shl 7
  const val STAIRS_BOTTOM = 1 shl 8
  const val CARPET_LIKE = 1 shl 9
  const val ETHER_PASSABLE = 1 shl 10
}
