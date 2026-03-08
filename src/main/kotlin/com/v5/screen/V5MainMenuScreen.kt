package com.v5.screen

import com.v5.render.NVGRenderer
import com.v5.render.helper.Font
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import net.minecraft.client.gui.screen.option.OptionsScreen
import net.minecraft.client.gui.screen.world.SelectWorldScreen
import net.minecraft.text.Text
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE

class V5MainMenuScreen : Screen(Text.literal("V5 Main Menu")) {
    private data class MenuButton(
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

    private val menuButtons = mutableListOf<MenuButton>()

    companion object {
        @JvmField
        val titleFont = Font("V5SegoeBold", "/assets/v5/SegoeTVBold.otf")

        @JvmField
        val smallerFont = Font("V5SegoeRegular", "/assets/v5/SegoeTVRegular.otf")

        @JvmStatic
        fun drawV5Button(label: String, x: Float, y: Float, width: Float, height: Float, hovered: Boolean) {
            val bg = if (hovered) argb(120, 73, 95, 124) else argb(82, 58, 72, 94)
            val border = if (hovered) argb(72, 244, 249, 255) else argb(56, 218, 228, 238)
            val textColor = if (hovered) argb(255, 255, 255, 255) else argb(252, 224, 232, 242)

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

    override fun init() {
        menuButtons.clear()
        val centerX = width / 2
        val multiplayerY = height / 2 - 10

        menuButtons += MenuButton("Singleplayer", centerX - 100, multiplayerY - 24, 200, 20) {
            client?.setScreen(SelectWorldScreen(this))
        }
        menuButtons += MenuButton("Multiplayer", centerX - 100, multiplayerY, 200, 20) {
            client?.setScreen(MultiplayerScreen(this))
        }
        menuButtons += MenuButton("Proxy Manager", centerX - 100, multiplayerY + 24, 200, 20) {
            client?.setScreen(ProxyManagerScreen(this))
        }
        menuButtons += MenuButton("Options", centerX - 100, multiplayerY + 48, 98, 20) {
            client?.setScreen(OptionsScreen(this, client!!.options))
        }
        menuButtons += MenuButton("Quit", centerX + 2, multiplayerY + 48, 98, 20) {
            client?.scheduleStop()
        }
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderPanoramaBackground(context, delta)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
        try {
            menuButtons.forEach { button ->
                drawV5Button(
                    button.label,
                    button.x.toFloat(),
                    button.y.toFloat(),
                    button.width.toFloat(),
                    button.height.toFloat(),
                    button.isHovered(mouseX, mouseY)
                )
            }
        } finally {
            NVGRenderer.endFrame()
        }

        val username = client?.session?.username ?: "Unknown"
        val multiplayerY = height / 2 - 10
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("EPSILON V5"), width / 2, multiplayerY - 52, 0xF5F8FFFF.toInt())
        context.drawTextWithShadow(textRenderer, Text.literal("Logged in as $username"), 8, height - 12, 0xE1E9F1FF.toInt())
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val clicked = menuButtons.firstOrNull { it.isHovered(click.x.toInt(), click.y.toInt()) }
        if (clicked != null) {
            clicked.onClick()
            return true
        }

        return super.mouseClicked(click, doubled)
    }
}
