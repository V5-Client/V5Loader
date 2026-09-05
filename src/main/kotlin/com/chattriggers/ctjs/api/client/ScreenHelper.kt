package com.chattriggers.ctjs.api.client

import com.chattriggers.ctjs.api.render.Font
import com.chattriggers.ctjs.api.render.Render2D
import net.minecraft.util.ARGB

object ScreenHelper {
    @JvmField
    val titleFont = Font("V5SegoeBold", "/assets/v5/SegoeTVBold.otf")

    @JvmField
    val smallerFont = Font("V5SegoeRegular", "/assets/v5/SegoeTVRegular.otf")

    data class MenuButton(
        val label: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val onClick: () -> Unit
    ) {
        fun isHovered(mouseX: Int, mouseY: Int): Boolean {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
        }
    }

    @JvmStatic
    fun drawMenuButton(
        label: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        hovered: Boolean,
        textColorOverride: Int? = null
    ) {
        val bg = if (hovered) argb(120, 73, 95, 124) else argb(82, 58, 72, 94)
        val border = if (hovered) argb(72, 244, 249, 255) else argb(56, 218, 228, 238)
        val defaultTextColor = if (hovered) argb(255, 255, 255, 255) else argb(252, 224, 232, 242)
        val textColor = textColorOverride ?: defaultTextColor

        Render2D.drawDropShadow(x, y, width, height, 5f, 14f, 1.3f, argb(60, 0, 10, 28))
        Render2D.drawRoundedRect(x, y, width, height, 5f, bg)
        Render2D.drawHollowRect(x, y, width, height, 0.65f, border, 5f)
        Render2D.text(
            label,
            x + width / 2f,
            y + height / 2f,
            8.7f,
            textColor,
            smallerFont,
            Render2D.ALIGN_CENTER or Render2D.ALIGN_MIDDLE
        )
    }

    @JvmStatic
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = ARGB.color(a, r, g, b)
}
