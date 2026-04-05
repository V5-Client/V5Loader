package com.v5.swift.cache

import com.v5.swift.nativepath.NativeVoxelFlags

class CachedChunk(
  @JvmField val minY: Int,
  @JvmField val maxY: Int
) {

  companion object {
    @JvmField
    val AIR_FLAGS: Short = (
      NativeVoxelFlags.PASSABLE or
        NativeVoxelFlags.PASSABLE_FLY or
        NativeVoxelFlags.ETHER_PASSABLE
      ).toShort()
  }

  private val sections: Array<ShortArray?> = arrayOfNulls((maxY - minY + 15) shr 4)

  @Volatile
  @JvmField
  var ready: Boolean = false

  fun getFlags(localX: Int, y: Int, localZ: Int): Short {
    if (y !in minY..<maxY) return AIR_FLAGS
    val sectionIndex = (y - minY) shr 4
    if (sectionIndex < 0 || sectionIndex >= sections.size) return AIR_FLAGS
    val section = sections[sectionIndex] ?: return AIR_FLAGS
    return section[((y and 15) shl 8) or ((localZ and 15) shl 4) or (localX and 15)]
  }

  fun setFlags(localX: Int, y: Int, localZ: Int, flags: Short) {
    if (y !in minY..<maxY) return

    val sectionIndex = (y - minY) shr 4
    if (sectionIndex < 0 || sectionIndex >= sections.size) return

    var section = sections[sectionIndex]
    if (section == null) {
      section = ShortArray(4096) { AIR_FLAGS }
      sections[sectionIndex] = section
    }

    section[((y and 15) shl 8) or ((localZ and 15) shl 4) or (localX and 15)] = flags
  }

  fun hasSection(index: Int): Boolean {
    return index in sections.indices && sections[index] != null
  }

  fun copySectionFlags(index: Int, dest: ShortArray, destOffset: Int) {
    val section = if (index in sections.indices) sections[index] else null
    if (section != null) {
      section.copyInto(dest, destOffset)
    }
  }

  fun setSection(sectionIndex: Int, data: ShortArray) {
    if (sectionIndex in sections.indices) {
      sections[sectionIndex] = data
    }
  }
}
