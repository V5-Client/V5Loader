package com.chattriggers.ctjs.api.render

import net.minecraft.client.gui.GuiGraphicsExtractor

object DrawContextHolder {
    @JvmField
    var currentContext: GuiGraphicsExtractor? = null

    inline fun <T> withContext(context: GuiGraphicsExtractor, block: () -> T): T {
        val previous = currentContext
        currentContext = context

        return try {
            block()
        } finally {
            currentContext = previous
        }
    }
}
