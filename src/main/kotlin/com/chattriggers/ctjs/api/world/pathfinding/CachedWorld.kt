package com.chattriggers.ctjs.api.world.pathfinding

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CachedWorld {

  @Volatile
  private var chunks = ConcurrentHashMap<Long, CachedChunk>(512)
  private val pendingChunks = ConcurrentLinkedQueue<Long>()
  private val dirtyNativeChunks = ConcurrentHashMap.newKeySet<Long>()
  private val pendingNativeUpdates = ConcurrentHashMap<Long, Int>(256)

  private const val RUNTIME_WORLD_KEY = "runtime_memory"
  @Volatile
  private var worldKey: String = RUNTIME_WORLD_KEY
  @Volatile
  private var nativeWorldToken: String = ""

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
      is ClientboundLevelChunkWithLightPacket -> {
        pendingChunks.add(chunkKey(packet.x, packet.z))
      }

      is ClientboundBlockUpdatePacket -> {
        updateCachedBlock(packet.pos, packet.blockState)
      }

      is ClientboundSectionBlocksUpdatePacket -> {
        packet.runUpdates { pos, state ->
          updateCachedBlock(pos, state)
        }
      }
    }
  }

  private fun updateCachedBlock(pos: BlockPos, state: BlockState) {
    val key = chunkKey(pos.x shr 4, pos.z shr 4)
    val chunk = chunks[key]?.takeIf { it.ready } ?: return
    val flags = NativeStateEncoder.flagsForState(state).toShort()
    if (chunk.getFlags(pos.x and 15, pos.y, pos.z and 15) == flags) return
    chunk.setFlags(pos.x and 15, pos.y, pos.z and 15, flags)
    queueNativeUpdate(pos.x, pos.y, pos.z, flags.toInt() and 0xFFFF)
    if (cacheKey == key) {
      cacheChunk = chunk
    }
  }

  fun processPendingChunks() {
    val mc = Minecraft.getInstance()
    val world = mc.level ?: return
    if (isCacheLoading) return

    val minY = world.minY
    val maxY = world.maxY + 1

    ensureNativeWorld(minY, maxY)

    repeat(Swift.CHUNKS_PER_TICK) {
      val next = pendingChunks.poll() ?: return@repeat
      val chunkX = (next shr 32).toInt()
      val chunkZ = next.toInt()

      val worldChunk = world.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
      if (worldChunk == null) {
        pendingChunks.add(chunkKey(chunkX, chunkZ))
        return@repeat
      }

      val cached = CachedChunk(minY, maxY)
      val sections = worldChunk.sections

      for (sectionIndex in sections.indices) {
        val section = sections[sectionIndex]
        if (section.hasOnlyAir()) continue

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

      dirtyNativeChunks.add(key)
    }

    if (!unlimitedChunkCache && chunks.size > Swift.MAXIMUM_CACHED_CHUNKS) {
      val toRemove = chunks.size - Swift.MAXIMUM_CACHED_CHUNKS
      val removed = chunks.keys.take(toRemove).filter { chunks.remove(it) != null }.toLongArray()
      NativePathfinderBridge.removeChunks(removed)
      if (cacheKey in removed) {
        cacheKey = Long.MIN_VALUE
        cacheChunk = null
      }
    }

    syncDirtyChunksToNative()
    flushPendingNativeUpdates()
  }

  private fun resetState() {
    chunks = ConcurrentHashMap(512)
    pendingChunks.clear()
    dirtyNativeChunks.clear()
    pendingNativeUpdates.clear()
    cacheKey = Long.MIN_VALUE
    cacheChunk = null
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
            if (sessionMap.putIfAbsent(key, chunk) == null) {
              dirtyNativeChunks.add(key)
            }
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
    NativePathfinderBridge.clearWorld()
  }

  private fun ensureNativeWorld(minY: Int, maxY: Int) {
    if (!NativePathfinderBridge.isAvailable()) return

    val token = "$worldKey|$minY|$maxY"
    if (token == nativeWorldToken) return

    NativePathfinderBridge.setWorld(worldKey, minY, maxY)
    if (NativePathfinderBridge.getLastError() == null) {
      nativeWorldToken = token
      dirtyNativeChunks.addAll(chunks.keys)
    }
  }

  private fun syncDirtyChunksToNative() {
    if (!NativePathfinderBridge.isAvailable() || dirtyNativeChunks.isEmpty()) return

    val readyChunks = dirtyNativeChunks.mapNotNull { key ->
      if (!dirtyNativeChunks.remove(key)) return@mapNotNull null
      chunks[key]?.takeIf { it.ready }?.let { key to it }
    }
    if (readyChunks.isEmpty()) return

    val metadata = IntArray(readyChunks.size * 4)
    val sectionMasks = LongArray(readyChunks.size)
    val sectionFlags = Array(readyChunks.size) { index ->
      val (key, chunk) = readyChunks[index]
      val offset = index * 4
      metadata[offset] = (key shr 32).toInt()
      metadata[offset + 1] = key.toInt()
      metadata[offset + 2] = chunk.minY
      metadata[offset + 3] = chunk.maxY

      val encoded = encodeChunk(chunk)
      sectionMasks[index] = encoded.first
      encoded.second
    }

    NativePathfinderBridge.upsertChunks(metadata, sectionMasks, sectionFlags)
    if (NativePathfinderBridge.getLastError() != null) {
      dirtyNativeChunks.addAll(readyChunks.map { it.first })
    }
  }

  private fun encodeChunk(chunk: CachedChunk): Pair<Long, ShortArray> {
    val sectionCount = minOf((chunk.maxY - chunk.minY + 15) shr 4, Long.SIZE_BITS)
    var sectionMask = 0L
    var totalValues = 0
    for (i in 0 until sectionCount) {
      if (chunk.hasSection(i)) {
        sectionMask = sectionMask or (1L shl i)
        totalValues += 4096
      }
    }

    val sectionFlags = ShortArray(totalValues)
    var offset = 0
    for (i in 0 until sectionCount) {
      if ((sectionMask and (1L shl i)) == 0L) continue
      chunk.copySectionFlags(i, sectionFlags, offset)
      offset += 4096
    }

    return sectionMask to sectionFlags
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
