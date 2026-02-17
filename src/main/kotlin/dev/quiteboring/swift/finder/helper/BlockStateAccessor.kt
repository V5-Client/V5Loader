package dev.quiteboring.swift.finder.helper

import dev.quiteboring.swift.Swift
import dev.quiteboring.swift.cache.CachedChunk
import dev.quiteboring.swift.cache.CachedWorld
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkSection

class BlockStateAccessor(val world: World) {

  @JvmField val mutablePos: BlockPos.Mutable = BlockPos.Mutable()
  @JvmField val access: BlockViewWrapper = BlockViewWrapper(this)

  private val air: BlockState = Blocks.AIR.defaultState
  private val bottomY: Int = world.bottomY
  private val topY: Int = world.topYInclusive

  private var worldChunkX = Int.MIN_VALUE
  private var worldChunkZ = Int.MIN_VALUE
  private var worldSections: Array<ChunkSection>? = null

  fun get(x: Int, y: Int, z: Int): BlockState {
    if (y !in bottomY..topY) {
      return air
    }

    if (Swift.settings.worldCache) {
      val state = CachedWorld.getBlockState(x, y, z)
      if (state != null) return state
    }

    return getFromWorld(x, y, z)
  }

  private fun getFromWorld(x: Int, y: Int, z: Int): BlockState {
    val chunkX = x shr 4
    val chunkZ = z shr 4

    if (chunkX != worldChunkX || chunkZ != worldChunkZ) {
      val chunk = world.getChunk(chunkX, chunkZ)
      worldChunkX = chunkX
      worldChunkZ = chunkZ
      worldSections = chunk.sectionArray
    }

    val sections = worldSections ?: return air
    val sectionIndex = (y - bottomY) shr 4

    if (sectionIndex !in sections.indices) {
      return air
    }

    val section = sections[sectionIndex]
    if (section.isEmpty) {
      return air
    }

    return section.getBlockState(x and 15, y and 15, z and 15)
  }

}
