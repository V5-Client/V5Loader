package com.v5.swift.util

import kotlin.math.ceil
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos

object PlayerUtils {

  private val mc = MinecraftClient.getInstance()

  fun getBlockStandingOn(): BlockPos? =
    mc.player?.let {
      BlockPos.ofFloored(it.x, ceil(it.y - 0.25), it.z)
    }

}
