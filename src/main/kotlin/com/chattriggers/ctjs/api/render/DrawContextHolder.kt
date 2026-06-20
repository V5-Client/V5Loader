package com.chattriggers.ctjs.api.render

import net.minecraft.client.gui.DrawContext

object DrawContextHolder {
    @JvmStatic
    var currentContext: DrawContext? = null
}
