package com.v5.swift.io

import com.v5.swift.cache.CachedChunk
import com.v5.swift.nativepath.NativeStateEncoder
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.collections.iterator

object WorldSerializer {

  private const val MAGIC = 0x5CAFEBAB
  private const val VERSION = 4
  private const val LEGACY_STATE_ID_VERSION = 3
  private val CACHE_DIR = File("pathfinder_cache")

  init {
    if (!CACHE_DIR.exists()) {
      CACHE_DIR.mkdirs()
    }
  }

  fun save(name: String, chunks: Map<Long, CachedChunk>) {
    if (!CACHE_DIR.exists()) {
      CACHE_DIR.mkdirs()
    }

    val file = File(CACHE_DIR, "$name.bin")
    val readyChunks = chunks.filterValues { it.ready }

    DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(file), 8192))).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(VERSION)
      out.writeInt(readyChunks.size)

      val byteBuffer = ByteBuffer.allocate(4096 * 2).order(ByteOrder.BIG_ENDIAN)
      val rawSection = ShortArray(4096)

      for ((key, chunk) in readyChunks) {
        out.writeLong(key)
        out.writeInt(chunk.minY)
        out.writeInt(chunk.maxY)

        val sectionCount = (chunk.maxY - chunk.minY + 15) shr 4
        var sectionMask = 0L
        for (i in 0 until sectionCount) {
          if (chunk.hasSection(i)) {
            sectionMask = sectionMask or (1L shl i)
          }
        }

        out.writeLong(sectionMask)

        for (i in 0 until sectionCount) {
          if ((sectionMask and (1L shl i)) != 0L) {
            rawSection.fill(CachedChunk.AIR_FLAGS)
            chunk.copySectionFlags(i, rawSection, 0)
            byteBuffer.clear()
            byteBuffer.asShortBuffer().put(rawSection)
            out.write(byteBuffer.array())
          }
        }

      }
    }
  }

  fun load(name: String): ConcurrentHashMap<Long, CachedChunk>? {
    val file = File(CACHE_DIR, "$name.bin")
    if (!file.exists()) return null

    try {
      DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
        if (input.readInt() != MAGIC) return null
        val version = input.readInt()
        if (version != VERSION && version != LEGACY_STATE_ID_VERSION) return null

        val count = input.readInt()
        val map = ConcurrentHashMap<Long, CachedChunk>(count)

        val sectionByteSize = if (version == VERSION) 4096 * 2 else 4096 * 4
        val rawBytes = ByteArray(sectionByteSize)

        for (k in 0 until count) {
          val key = input.readLong()
          val minY = input.readInt()
          val maxY = input.readInt()

          val chunk = CachedChunk(minY, maxY)

          val sectionMask = input.readLong()
          val sectionCount = (maxY - minY + 15) shr 4

          for (i in 0 until sectionCount) {
            if ((sectionMask and (1L shl i)) != 0L) {
              input.readFully(rawBytes)
              chunk.setSection(i, decodeSection(version, rawBytes))
            }
          }

          chunk.ready = true
          map[key] = chunk
        }

        return map
      }
    } catch (e: Exception) {
      e.printStackTrace()
      return null
    }
  }

  private fun decodeSection(version: Int, rawBytes: ByteArray): ShortArray {
    return if (version == VERSION) {
      val section = ShortArray(4096)
      ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(section)
      section
    } else {
      val stateIds = IntArray(4096)
      ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(stateIds)
      ShortArray(4096) { index -> NativeStateEncoder.flagsShortForStateId(stateIds[index]) }
    }
  }
}
