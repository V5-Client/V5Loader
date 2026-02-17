package dev.quiteboring.swift.cache

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks

class CachedChunk(
  @JvmField val minY: Int,
  @JvmField val maxY: Int
) {

  companion object {
    @JvmField val AIR: BlockState = Blocks.AIR.defaultState
    @JvmField val AIR_ID: Int = Block.STATE_IDS.getRawId(AIR)
  }

  private val sections: Array<IntArray?> = arrayOfNulls((maxY - minY + 15) shr 4)

  // remember that in WDC: bits 0, 1, 2 = edge, bits 3, 4, 5 = wall, bit 6 = flag
  private val distanceData: Array<ByteArray?> = arrayOfNulls(maxY - minY)

  @Volatile
  @JvmField
  var ready: Boolean = false

  fun get(localX: Int, y: Int, localZ: Int): BlockState {
    val stateId = getStateId(localX, y, localZ)
    return if (stateId == AIR_ID) AIR else Block.STATE_IDS.get(stateId) ?: AIR
  }

  fun getStateId(localX: Int, y: Int, localZ: Int): Int {
    if (y !in minY..<maxY) return AIR_ID
    val sectionIndex = (y - minY) shr 4
    if (sectionIndex < 0 || sectionIndex >= sections.size) return AIR_ID
    val section = sections[sectionIndex] ?: return AIR_ID
    return section[((y and 15) shl 8) or ((localZ and 15) shl 4) or (localX and 15)]
  }

  fun set(localX: Int, y: Int, localZ: Int, state: BlockState) {
    if (y !in minY..<maxY) return

    val sectionIndex = (y - minY) shr 4
    if (sectionIndex < 0 || sectionIndex >= sections.size) return

    var section = sections[sectionIndex]
    if (section == null) {
      section = IntArray(4096) { AIR_ID }
      sections[sectionIndex] = section
    }

    section[((y and 15) shl 8) or ((localZ and 15) shl 4) or (localX and 15)] =
      Block.STATE_IDS.getRawId(state)

    invalidateDistanceCache(y)
  }

  fun hasSection(index: Int): Boolean {
    return index in sections.indices && sections[index] != null
  }

  fun getSectionData(index: Int): IntArray? {
    return if (index in sections.indices) sections[index] else null
  }

  fun setDistanceDataByIndex(yIndex: Int, data: ByteArray) {
    if (yIndex in distanceData.indices) {
      distanceData[yIndex] = data
    }
  }

  fun setSection(sectionIndex: Int, data: IntArray) {
    if (sectionIndex in sections.indices) {
      sections[sectionIndex] = data
    }
  }

  private fun invalidateDistanceCache(y: Int) {
    for (dy in -3..1) {
      val yIndex = y + dy - minY
      if (yIndex in distanceData.indices) {
        distanceData[yIndex] = null
      }
    }
  }

  fun getDistanceData(y: Int): ByteArray? {
    val yIndex = y - minY
    if (yIndex !in distanceData.indices) return null
    return distanceData[yIndex]
  }

  fun setDistanceData(y: Int, data: ByteArray) {
    val yIndex = y - minY
    if (yIndex in distanceData.indices) {
      distanceData[yIndex] = data
    }
  }

}
