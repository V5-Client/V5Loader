package com.v5.render.helper

import net.minecraft.client.gui.DrawContext

object DrawContextHolder {
    @JvmStatic
    var currentContext: DrawContext? = null
}