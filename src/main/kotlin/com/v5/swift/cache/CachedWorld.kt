package com.v5.swift.cache

import com.v5.swift.Swift
import com.v5.swift.io.WorldSerializer
import com.v5.swift.nativepath.NativePathfinderBridge
import com.v5.swift.nativepath.NativeStateEncoder
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

  @Volatile
  private var chunks = ConcurrentHashMap<Long, CachedChunk>(512)
  private val pendingChunks = ConcurrentLinkedQueue<Long>()
  private val pendingNativeUpdates = ConcurrentHashMap<Long, Int>(256)

  private const val RUNTIME_WORLD_KEY = "runtime_memory"
  @Volatile
  private var worldKey: String = RUNTIME_WORLD_KEY
  @Volatile
  private var nativeWorldToken: String = ""
  @Volatile
  private var pendingNativeResync = true

  private var cacheKey: Long = Long.MIN_VALUE
  private var cacheChunk: CachedChunk? = null

  private val loadLock = ReentrantLock()
  private val loadCondition = loadLock.newCondition()
  @Volatile
  private var isCacheLoading = false
  @Volatile
  private var unlimitedChunkCache = false

  private fun chunkKey(x: Int, z: Int): Long =
    (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

  private fun blockKey(x: Int, y: Int, z: Int): Long {
    val packedX = (x.toLong() + 33_554_432L) and 0x3FFFFFFL
    val packedY = (y.toLong() + 2_048L) and 0xFFFL
    val packedZ = (z.toLong() + 33_554_432L) and 0x3FFFFFFL
    return (packedX shl 38) or (packedY shl 26) or packedZ
  }

  private fun unpackBlockX(key: Long): Int = ((key ushr 38) and 0x3FFFFFFL).toInt() - 33_554_432
  private fun unpackBlockY(key: Long): Int = ((key ushr 26) and 0xFFFL).toInt() - 2_048
  private fun unpackBlockZ(key: Long): Int = (key and 0x3FFFFFFL).toInt() - 33_554_432

  @JvmStatic
  fun getBlockFlags(x: Int, y: Int, z: Int): Short? {
    val chunkX = x shr 4
    val chunkZ = z shr 4
    val key = chunkKey(chunkX, chunkZ)

    val cached = cacheChunk
    if (cacheKey == key && cached != null && cached.ready) {
      return cached.getFlags(x and 15, y, z and 15)
    }

    val chunk = chunks[key] ?: return null
    if (!chunk.ready) return null

    cacheKey = key
    cacheChunk = chunk

    return chunk.getFlags(x and 15, y, z and 15)
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
        pendingChunks.add(chunkKey(packet.chunkX, packet.chunkZ))
      }

      is BlockUpdateS2CPacket -> {
        val pos = packet.pos
        val key = chunkKey(pos.x shr 4, pos.z shr 4)
        val chunk = chunks[key]
        if (chunk != null && chunk.ready) {
          val flags = NativeStateEncoder.flagsForState(packet.state).toShort()
          chunk.setFlags(pos.x and 15, pos.y, pos.z and 15, flags)
          queueNativeUpdate(pos.x, pos.y, pos.z, flags.toInt() and 0xFFFF)
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
            val flags = NativeStateEncoder.flagsForState(state).toShort()
            chunk.setFlags(pos.x and 15, pos.y, pos.z and 15, flags)
            queueNativeUpdate(pos.x, pos.y, pos.z, flags.toInt() and 0xFFFF)
            if (cacheKey == key) {
              cacheChunk = chunk
            }
          }
        }
      }
    }
  }

  fun processPendingChunks() {
    val mc = MinecraftClient.getInstance()
    val world = mc.world ?: return
    if (isCacheLoading) return

    val minY = world.bottomY
    val maxY = world.topYInclusive + 1

    ensureNativeWorld(minY, maxY)

    repeat(Swift.CHUNKS_PER_TICK) {
      val next = pendingChunks.poll() ?: return@repeat
      val chunkX = (next shr 32).toInt()
      val chunkZ = next.toInt()

      val worldChunk = world.chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
      if (worldChunk == null) {
        pendingChunks.add(chunkKey(chunkX, chunkZ))
        return@repeat
      }

      val cached = CachedChunk(minY, maxY)
      val sections = worldChunk.sectionArray

      for (sectionIndex in sections.indices) {
        val section = sections[sectionIndex]
        if (section.isEmpty) continue

        val sectionData = ShortArray(4096) { CachedChunk.AIR_FLAGS }

        for (localY in 0..15) {
          val yOffset = localY shl 8
          for (localZ in 0..15) {
            val zOffset = localZ shl 4
            for (localX in 0..15) {
              sectionData[yOffset or zOffset or localX] =
                NativeStateEncoder.flagsShortForState(section.getBlockState(localX, localY, localZ))
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

      syncChunkToNative(chunkX, chunkZ, cached)
    }

    if (!unlimitedChunkCache && chunks.size > Swift.MAXIMUM_CACHED_CHUNKS) {
      val toRemove = chunks.size - Swift.MAXIMUM_CACHED_CHUNKS
      chunks.keys.take(toRemove).forEach { chunks.remove(it) }
    }

    if (pendingNativeResync) {
      syncAllCachedChunksToNative()
      pendingNativeResync = false
    }

    flushPendingNativeUpdates()
  }

  private fun resetState() {
    chunks = ConcurrentHashMap(512)
    pendingChunks.clear()
    pendingNativeUpdates.clear()
    cacheKey = Long.MIN_VALUE
    cacheChunk = null
    pendingNativeResync = true
    nativeWorldToken = ""
    NativePathfinderBridge.clearWorld()
  }

  fun saveAndClear(lobbyName: String) {
    val mapToSave = chunks
    resetState()
    setLoadingState(false)

    if (mapToSave.isNotEmpty()) {
      Swift.executor.submit {
        try {
          WorldSerializer.save(lobbyName, mapToSave)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
  }

  fun load(lobbyName: String) {
    resetState()
    val sessionMap = chunks
    setLoadingState(true)

    Swift.executor.submit {
      try {
        val loaded = WorldSerializer.load(lobbyName)
        if (loaded != null && chunks === sessionMap) {
          for ((key, chunk) in loaded) {
            sessionMap.putIfAbsent(key, chunk)
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      } finally {
        if (chunks === sessionMap) setLoadingState(false)
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
    resetState()
    setLoadingState(false)
  }

  fun getCacheStats(): String {
    val currentChunks = chunks
    val ready = currentChunks.values.count { it.ready }
    return "Cached: $ready, Pending: ${pendingChunks.size}, Loading: $isCacheLoading"
  }

  fun setUnlimitedChunkCache(enabled: Boolean) {
    unlimitedChunkCache = enabled
  }

  fun setWorldKey(newWorldKey: String?) {
    val normalized = newWorldKey?.ifBlank { RUNTIME_WORLD_KEY } ?: RUNTIME_WORLD_KEY
    if (worldKey == normalized) return

    worldKey = normalized
    nativeWorldToken = ""
    pendingNativeResync = true
    NativePathfinderBridge.clearWorld()
  }

  private fun ensureNativeWorld(minY: Int, maxY: Int) {
    if (!NativePathfinderBridge.isAvailable()) return

    val token = "$worldKey|$minY|$maxY"
    if (token == nativeWorldToken) return

    NativePathfinderBridge.setWorld(worldKey, minY, maxY)
    if (NativePathfinderBridge.getLastError() == null) {
      nativeWorldToken = token
      pendingNativeResync = true
    }
  }

  private fun syncAllCachedChunksToNative() {
    if (!NativePathfinderBridge.isAvailable()) return

    for ((key, chunk) in chunks) {
      if (!chunk.ready) continue
      val chunkX = (key shr 32).toInt()
      val chunkZ = key.toInt()
      syncChunkToNative(chunkX, chunkZ, chunk)
    }
  }

  private fun syncChunkToNative(chunkX: Int, chunkZ: Int, chunk: CachedChunk) {
    if (!NativePathfinderBridge.isAvailable() || !chunk.ready) return

    val sectionCount = (chunk.maxY - chunk.minY + 15) shr 4
    var sectionMask = 0L
    var totalValues = 0
    for (i in 0 until sectionCount) {
      if (chunk.hasSection(i)) {
        sectionMask = sectionMask or (1L shl i)
        totalValues += 4096
      }
    }

    if (totalValues == 0) {
      NativePathfinderBridge.upsertChunk(
        chunkX,
        chunkZ,
        chunk.minY,
        chunk.maxY,
        0L,
        ShortArray(0)
      )
      return
    }

    val sectionFlags = ShortArray(totalValues)
    var offset = 0
    for (i in 0 until sectionCount) {
      if ((sectionMask and (1L shl i)) == 0L) continue
      chunk.copySectionFlags(i, sectionFlags, offset)
      offset += 4096
    }

    NativePathfinderBridge.upsertChunk(
      chunkX,
      chunkZ,
      chunk.minY,
      chunk.maxY,
      sectionMask,
      sectionFlags
    )
  }

  private fun flushPendingNativeUpdates() {
    if (!NativePathfinderBridge.isAvailable()) {
      pendingNativeUpdates.clear()
      return
    }
    if (pendingNativeUpdates.isEmpty()) return

    var updates = IntArray(maxOf(16, pendingNativeUpdates.size * 4))
    var offset = 0
    pendingNativeUpdates.forEach { key, flags ->
      if (!pendingNativeUpdates.remove(key, flags)) {
        return@forEach
      }

      if (offset + 4 > updates.size) {
        updates = updates.copyOf(maxOf(updates.size shl 1, offset + 4))
      }

      updates[offset] = unpackBlockX(key)
      updates[offset + 1] = unpackBlockY(key)
      updates[offset + 2] = unpackBlockZ(key)
      updates[offset + 3] = flags
      offset += 4
    }

    if (offset == 0) return
    NativePathfinderBridge.applyBlockUpdates(if (offset == updates.size) updates else updates.copyOf(offset))
  }

  private fun queueNativeUpdate(x: Int, y: Int, z: Int, flags: Int) {
    if (!NativePathfinderBridge.isAvailable()) return
    pendingNativeUpdates[blockKey(x, y, z)] = flags
  }
}
