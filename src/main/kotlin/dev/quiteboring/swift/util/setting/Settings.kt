package dev.quiteboring.swift.util.setting

class Settings {

  /**
   * Determines where you use world caching or not (may impact performance)
   */
  var worldCache = true

  /**
   * If using world cache, it determines how many chunks you cache per tick
   */
  var chunksPerTick = 8

  /**
   * If using world cache, it limits the number of chunks cached
   */
  var maximumCachedChunks = 4096

}
