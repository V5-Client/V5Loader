package com.v5.screen

import com.v5.render.NVGRenderer
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import net.minecraft.client.gui.screen.option.OptionsScreen
import net.minecraft.client.gui.screen.world.SelectWorldScreen
import net.minecraft.text.Text
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_BOTTOM
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_TOP

class V5MainMenuScreen : Screen(Text.literal("V5 Main Menu")) {
    private data class TitleBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(mouseX: Float, mouseY: Float): Boolean {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom
        }
    }

    private val titleText = "RDBT V5"
    private val menuButtons = mutableListOf<ScreenHelper.MenuButton>()
    private var titleBounds = TitleBounds(0f, 0f, 0f, 0f)

    override fun init() {
        menuButtons.clear()
        val centerX = width / 2
        val multiplayerY = height / 2 - 10

        menuButtons += ScreenHelper.MenuButton("Singleplayer", centerX - 100, multiplayerY - 24, 200, 20) {
            client?.setScreen(SelectWorldScreen(this))
        }
        menuButtons += ScreenHelper.MenuButton("Multiplayer", centerX - 100, multiplayerY, 200, 20) {
            client?.setScreen(MultiplayerScreen(this))
        }
        menuButtons += ScreenHelper.MenuButton("Proxy Manager", centerX - 100, multiplayerY + 24, 200, 20) {
            client?.setScreen(ProxyManagerScreen(this))
        }
        menuButtons += ScreenHelper.MenuButton("Options", centerX - 100, multiplayerY + 48, 98, 20) {
            client?.setScreen(OptionsScreen(this, client!!.options))
        }
        menuButtons += ScreenHelper.MenuButton("Quit", centerX + 2, multiplayerY + 48, 98, 20) {
            client?.scheduleStop()
        }
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderPanoramaBackground(context, delta)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val username = client?.session?.username ?: "Unknown"
        val multiplayerY = height / 2 - 10
        val titleY = (multiplayerY - 64).toFloat()

        NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
        try {
            val titleWidth = NVGRenderer.textWidth(titleText, 30f, ScreenHelper.titleFont)
            titleBounds = TitleBounds(
                width / 2f - titleWidth / 2f - 10f,
                titleY - 6f,
                width / 2f + titleWidth / 2f + 10f,
                titleY + 38f
            )
            val titleHovered = titleBounds.contains(mouseX.toFloat(), mouseY.toFloat())

            menuButtons.forEach { button ->
                ScreenHelper.drawMenuButton(
                    button.label,
                    button.x.toFloat(),
                    button.y.toFloat(),
                    button.width.toFloat(),
                    button.height.toFloat(),
                    button.isHovered(mouseX, mouseY),
                    null
                )
            }

            NVGRenderer.text(
                titleText,
                width / 2f,
                titleY,
                30f,
                if (titleHovered) ScreenHelper.argb(255, 255, 210, 210) else 0xF5F8FFFF.toInt(),
                ScreenHelper.titleFont,
                NVG_ALIGN_CENTER or NVG_ALIGN_TOP
            )

            NVGRenderer.text(
                "Logged in as $username",
                8f,
                height - 8f,
                8.7f,
                0xE1E9F1FF.toInt(),
                ScreenHelper.smallerFont,
                NVG_ALIGN_LEFT or NVG_ALIGN_BOTTOM
            )
        } finally {
            NVGRenderer.endFrame()
        }
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
