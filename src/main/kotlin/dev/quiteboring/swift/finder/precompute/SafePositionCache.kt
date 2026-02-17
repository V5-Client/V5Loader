package dev.quiteboring.swift.finder.precompute

import dev.quiteboring.swift.finder.calculate.PathNode
import dev.quiteboring.swift.finder.movement.CalculationContext
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap

class SafePositionCache(private val ctx: CalculationContext) {

  private val cache = Long2ByteOpenHashMap(8192, 0.6f).apply {
    defaultReturnValue(UNKNOWN)
  }

  fun isSafe(x: Int, y: Int, z: Int): Boolean {
    val key = PathNode.coordKey(x, y, z)
    val cached = cache.get(key)
    if (cached != UNKNOWN) return cached == SAFE

    val bsa = ctx.bsa
    val pre = ctx.precomputedData

    val groundState = bsa.get(x, y - 1, z)
    if (!pre.isSolid(x, y - 1, z, groundState)) {
      cache.put(key, NOT_SAFE)
      return false
    }

    val feetState = bsa.get(x, y, z)
    if (!pre.isPassable(x, y, z, feetState)) {
      cache.put(key, NOT_SAFE)
      return false
    }

    val headState = bsa.get(x, y + 1, z)
    val safe = pre.isPassable(x, y + 1, z, headState)
    cache.put(key, if (safe) SAFE else NOT_SAFE)
    return safe
  }

  fun clear() =
    cache.clear()

  companion object {
    private const val UNKNOWN: Byte = -1
    private const val NOT_SAFE: Byte = 0
    private const val SAFE: Byte = 1
  }

}
