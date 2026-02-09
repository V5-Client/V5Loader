package com.v5.qol

import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos

object Xray {
    @JvmField
    var isEnabled: Boolean = false
    @JvmField
    var alpha: Int = 100


    @JvmStatic
    fun setEnabled() {
        isEnabled = true
        MinecraftClient.getInstance().worldRenderer.reload()
    }

    @JvmStatic
    fun setDisabled() {
        isEnabled = false
        MinecraftClient.getInstance().worldRenderer.reload()
    }

    @JvmStatic
    fun setAlpha(newAlpha: Int) {
        alpha = newAlpha
    }

    @JvmStatic
    fun returnAlpha(pos: BlockPos?, state: BlockState?): Int {
        return alpha
    }
}