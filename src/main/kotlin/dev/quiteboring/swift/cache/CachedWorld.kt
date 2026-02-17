package dev.quiteboring.swift.cache

import dev.quiteboring.swift.Swift
import dev.quiteboring.swift.io.WorldSerializer
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket
import net.minecraft.world.chunk.ChunkStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CachedWorld {

  private val AIR: BlockState = Blocks.AIR.defaultState
  private val AIR_ID: Int = Block.STATE_IDS.getRawId(AIR)

  @Volatile
  private var chunks = ConcurrentHashMap<Long, CachedChunk>(512)
  private val pendingChunks = ConcurrentLinkedQueue<Pair<Int, Int>>()

  private var cacheKey: Long = Long.MIN_VALUE
  private var cacheChunk: CachedChunk? = null

  private val loadLock = ReentrantLock()
  private val loadCondition = loadLock.newCondition()
  @Volatile
  private var isCacheLoading = false

  private fun chunkKey(x: Int, z: Int): Long =
    (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

  @JvmStatic
  fun getBlockState(x: Int, y: Int, z: Int): BlockState? {
    val chunkX = x shr 4
    val chunkZ = z shr 4
    val key = chunkKey(chunkX, chunkZ)

    val cached = cacheChunk
    if (cacheKey == key && cached != null && cached.ready) {
      return cached.get(x and 15, y, z and 15)
    }

    val chunk = chunks[key] ?: return null
    if (!chunk.ready) return null

    cacheKey = key
    cacheChunk = chunk

    return chunk.get(x and 15, y, z and 15)
  }

  @JvmStatic
  fun getChunk(x: Int, z: Int): CachedChunk? {
    val key = chunkKey(x, z)
    val chunk = chunks[key]
    return if (chunk?.ready == true) chunk else null
  }

  fun onPacketReceive(packet: Packet<*>) {
    when (packet) {
      is ChunkDataS2CPacket -> {
        pendingChunks.add(packet.chunkX to packet.chunkZ)
      }

      is BlockUpdateS2CPacket -> {
        val pos = packet.pos
        val key = chunkKey(pos.x shr 4, pos.z shr 4)
        val chunk = chunks[key]
        if (chunk != null && chunk.ready) {
          chunk.set(pos.x and 15, pos.y, pos.z and 15, packet.state)
          if (cacheKey == key) {
            cacheChunk = chunk
          }
        }
      }

      is ChunkDeltaUpdateS2CPacket -> {
        packet.visitUpdates { pos, state ->
          val key = chunkKey(pos.x shr 4, pos.z shr 4)
          val chunk = chunks[key]
          if (chunk != null && chunk.ready) {
            chunk.set(pos.x and 15, pos.y, pos.z and 15, state)
          }
        }
      }
    }
  }

  fun processPendingChunks() {
    val mc = MinecraftClient.getInstance()
    val world = mc.world ?: return

    val minY = world.bottomY
    val maxY = world.topYInclusive + 1

    repeat(Swift.settings.chunksPerTick) {
      val (chunkX, chunkZ) = pendingChunks.poll() ?: return

      val worldChunk = world.chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
      if (worldChunk == null) {
        pendingChunks.add(chunkX to chunkZ)
        return@repeat
      }

      val cached = CachedChunk(minY, maxY)
      val sections = worldChunk.sectionArray

      for (sectionIndex in sections.indices) {
        val section = sections[sectionIndex]
        if (section.isEmpty) continue

        val sectionData = IntArray(4096) { AIR_ID }

        for (localY in 0..15) {
          val yOffset = localY shl 8
          for (localZ in 0..15) {
            val zOffset = localZ shl 4
            for (localX in 0..15) {
              val state = section.getBlockState(localX, localY, localZ)
              sectionData[yOffset or zOffset or localX] = Block.STATE_IDS.getRawId(state)
            }
          }
        }

        cached.setSection(sectionIndex, sectionData)
      }

      cached.ready = true
      val key = chunkKey(chunkX, chunkZ)
      chunks[key] = cached

      if (cacheKey == key) {
        cacheChunk = cached
      }
    }

    if (chunks.size > Swift.settings.maximumCachedChunks) {
      val toRemove = chunks.size - Swift.settings.maximumCachedChunks
      chunks.keys.take(toRemove).forEach { chunks.remove(it) }
    }
  }

  fun saveAndClear(lobbyName: String) {
    if (chunks.isEmpty()) return

    val mapToSave = chunks

    chunks = ConcurrentHashMap(512)
    pendingChunks.clear()
    cacheKey = Long.MIN_VALUE
    cacheChunk = null

    Swift.executor.submit {
      try {
        WorldSerializer.save(lobbyName, mapToSave)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun load(lobbyName: String) {
    setLoadingState(true)

    Swift.executor.submit {
      try {
        val loaded = WorldSerializer.load(lobbyName)

        if (loaded != null) {
          chunks = loaded
        }
      } catch (e: Exception) {
        e.printStackTrace()
      } finally {
        setLoadingState(false)
      }
    }
  }

  private fun setLoadingState(loading: Boolean) {
    loadLock.withLock {
      isCacheLoading = loading
      if (!loading) {
        loadCondition.signalAll()
      }
    }
  }

  fun waitForLoad() {
    if (!isCacheLoading) return
    loadLock.withLock {
      while (isCacheLoading) {
        loadCondition.await()
      }
    }
  }

  fun clear() {
    chunks = ConcurrentHashMap(512)
    pendingChunks.clear()
    cacheKey = Long.MIN_VALUE
    cacheChunk = null
  }

  fun getCacheStats(): String {
    val currentChunks = chunks
    val ready = currentChunks.values.count { it.ready }
    return "Cached: $ready, Pending: ${pendingChunks.size}, Loading: $isCacheLoading"
  }
}
