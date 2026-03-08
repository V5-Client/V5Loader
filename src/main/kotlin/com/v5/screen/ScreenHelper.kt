package com.v5.screen

import com.v5.render.NVGRenderer
import com.v5.render.helper.Font
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE

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

        NVGRenderer.drawDropShadow(x, y, width, height, 5f, 14f, 1.3f, argb(60, 0, 10, 28))
        NVGRenderer.drawRoundedRect(x, y, width, height, 5f, bg)
        NVGRenderer.drawHollowRect(x, y, width, height, 0.65f, border, 5f)
        NVGRenderer.text(
            label,
            x + width / 2f,
            y + height / 2f,
            8.7f,
            textColor,
            smallerFont,
            NVG_ALIGN_CENTER or NVG_ALIGN_MIDDLE
        )
    }

    @JvmStatic
    fun argb(a: Int, r: Int, g: Int, b: Int): Int {
        return ((a and 255) shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
    }
}
