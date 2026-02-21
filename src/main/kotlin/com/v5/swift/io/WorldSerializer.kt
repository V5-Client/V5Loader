package com.v5.swift.io

import com.v5.swift.cache.CachedChunk
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.collections.iterator

object WorldSerializer {

  private const val MAGIC = 0x5CAFEBAB
  private const val VERSION = 1
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

    DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(file), 8192))).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(VERSION)
      out.writeInt(chunks.size)

      val byteBuffer = ByteBuffer.allocate(4096 * 4).order(ByteOrder.BIG_ENDIAN)
      val intBuffer = byteBuffer.asIntBuffer()

      for ((key, chunk) in chunks) {
        if (!chunk.ready) continue

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
            val data = chunk.getSectionData(i)!!
            intBuffer.clear()
            intBuffer.put(data)
            out.write(byteBuffer.array())
          }
        }

        val validDistances = ArrayList<Pair<Int, ByteArray>>()
        for (y in chunk.minY until chunk.maxY) {
          val dist = chunk.getDistanceData(y)
          if (dist != null) validDistances.add((y - chunk.minY) to dist)
        }

        out.writeInt(validDistances.size)
        for ((yIndex, data) in validDistances) {
          out.writeShort(yIndex)
          out.write(data)
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
        if (input.readInt() != VERSION) return null

        val count = input.readInt()
        val map = ConcurrentHashMap<Long, CachedChunk>(count)

        val byteBuffer = ByteBuffer.allocate(4096 * 4).order(ByteOrder.BIG_ENDIAN)
        val intBuffer = byteBuffer.asIntBuffer()
        val rawBytes = ByteArray(4096 * 4)

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
              val intData = IntArray(4096)
              ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(intData)
              chunk.setSection(i, intData)
            }
          }

          val distCount = input.readInt()
          for (d in 0 until distCount) {
            val yIndex = input.readShort().toInt()
            val data = ByteArray(256)
            input.readFully(data)
            chunk.setDistanceDataByIndex(yIndex, data)
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
}
