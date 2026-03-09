package com.v5.visuals

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.ChunkStatus

object StructureFinder {
    private const val MAX_SCAN_RETRIES = 3
    private const val RETRY_DELAY_MS = 30L

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "V5-StructureFinder-Worker").apply { isDaemon = true }
    }

    private val stateLock = Any()
    private val pendingLock = Any()
    private val chunkBlocks = Long2ObjectOpenHashMap<IntOpenHashSet>(256)
    private val pendingScans = LongOpenHashSet(256)

    @Volatile
    private var cachedRenderArray = IntArray(0)

    @Volatile
    private var dirty = false

    @Volatile
    private var worldBottomY = -64

    @JvmStatic
    fun submitChunkScan(chunkX: Int, chunkZ: Int) {
        val key = ChunkPos.toLong(chunkX, chunkZ)
        synchronized(pendingLock) {
            if (!pendingScans.add(key)) return
        }

        worker.execute {
            try {
                scanChunk(chunkX, chunkZ, MAX_SCAN_RETRIES)
            } finally {
                synchronized(pendingLock) {
                    pendingScans.remove(key)
                }
            }
        }
    }

    @JvmStatic
    fun submitBlockUpdate(blockX: Int, blockY: Int, blockZ: Int) {
        worker.execute { updateBlock(blockX, blockY, blockZ) }
    }

    @JvmStatic
    fun getRenderBlocksArray(): IntArray {
        if (!dirty) return cachedRenderArray

        synchronized(stateLock) {
            if (!dirty) return cachedRenderArray
            cachedRenderArray = buildRenderArrayLocked()
            dirty = false
            return cachedRenderArray
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(stateLock) {
            chunkBlocks.clear()
            cachedRenderArray = IntArray(0)
            dirty = false
        }
        synchronized(pendingLock) {
            pendingScans.clear()
        }
    }

    @JvmStatic
    fun shutdown() {
        worker.shutdownNow()
        clear()
    }

    private fun scanChunk(chunkX: Int, chunkZ: Int, retriesLeft: Int) {
        val world = MinecraftClient.getInstance().world ?: return
        worldBottomY = world.bottomY

        val chunk = world.chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
        if (chunk == null) {
            if (retriesLeft > 0) {
                worker.schedule({ scanChunk(chunkX, chunkZ, retriesLeft - 1) }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
            }
            return
        }

        if (chunk.isEmpty) {
            removeChunk(ChunkPos.toLong(chunkX, chunkZ))
            return
        }

        val matched = IntOpenHashSet()
        val sections = chunk.sectionArray
        val minY = world.bottomY

        for (sectionIndex in sections.indices) {
            val section = sections[sectionIndex]
            if (section.isEmpty) continue

            val sectionBaseY = (sectionIndex shl 4) + minY
            for (localY in 0..15) {
                val y = sectionBaseY + localY
                val packedY = y - minY
                if (packedY < 0) continue

                for (localZ in 0..15) {
                    val zBits = localZ shl 4
                    for (localX in 0..15) {
                        val state = section.getBlockState(localX, localY, localZ)
                        if (state.isAir) continue
                        val key = state.block.translationKey
                        if (!isTargetKey(key)) continue

                        matched.add((packedY shl 8) or zBits or localX)
                    }
                }
            }
        }

        val chunkKey = ChunkPos.toLong(chunkX, chunkZ)
        synchronized(stateLock) {
            if (matched.isEmpty()) {
                chunkBlocks.remove(chunkKey)
            } else {
                chunkBlocks.put(chunkKey, matched)
            }
            dirty = true
        }
    }

    private fun updateBlock(blockX: Int, blockY: Int, blockZ: Int) {
        val world = MinecraftClient.getInstance().world ?: return
        worldBottomY = world.bottomY

        val state = world.getBlockState(BlockPos(blockX, blockY, blockZ))
        val isTarget = isTargetKey(state.block.translationKey)
        val chunkX = blockX shr 4
        val chunkZ = blockZ shr 4
        val chunkKey = ChunkPos.toLong(chunkX, chunkZ)
        val packed = packLocal(blockX, blockY, blockZ, world.bottomY)

        if (packed < 0) return

        synchronized(stateLock) {
            if (isTarget) {
                val set = chunkBlocks.get(chunkKey) ?: IntOpenHashSet(8).also { chunkBlocks.put(chunkKey, it) }
                if (set.add(packed)) dirty = true
            } else {
                val set = chunkBlocks.get(chunkKey) ?: return
                if (set.remove(packed)) {
                    if (set.isEmpty()) chunkBlocks.remove(chunkKey)
                    dirty = true
                }
            }
        }
    }

    private fun removeChunk(chunkKey: Long) {
        synchronized(stateLock) {
            if (chunkBlocks.remove(chunkKey) != null) {
                dirty = true
            }
        }
    }

    private fun buildRenderArrayLocked(): IntArray {
        if (chunkBlocks.isEmpty()) return IntArray(0)

        var total = 0
        val iterator = chunkBlocks.values.iterator()
        while (iterator.hasNext()) {
            total += iterator.next().size
        }

        val out = IntArray(total * 3)
        var idx = 0
        val bottomY = worldBottomY

        val entryIterator = chunkBlocks.long2ObjectEntrySet().fastIterator()
        while (entryIterator.hasNext()) {
            val entry = entryIterator.next()
            val chunkX = ChunkPos.getPackedX(entry.longKey)
            val chunkZ = ChunkPos.getPackedZ(entry.longKey)
            val baseX = chunkX shl 4
            val baseZ = chunkZ shl 4
            val blockSet = entry.value

            val blockIterator = blockSet.iterator()
            while (blockIterator.hasNext()) {
                val packed = blockIterator.nextInt()
                out[idx++] = baseX + (packed and 15)
                out[idx++] = bottomY + (packed ushr 8)
                out[idx++] = baseZ + ((packed ushr 4) and 15)
            }
        }

        return out
    }

    private fun packLocal(x: Int, y: Int, z: Int, bottomY: Int): Int {
        val localY = y - bottomY
        if (localY !in 0..1023) return -1
        return (localY shl 8) or ((z and 15) shl 4) or (x and 15)
    }

    private fun isTargetKey(key: String): Boolean {
        return key.contains("glass") || key.contains("coal")
    }
}
