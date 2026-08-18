package com.chattriggers.ctjs.api.client

import com.chattriggers.ctjs.api.Config
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import net.minecraft.util.Util
import java.awt.Color

class WelcomeScreen : Screen(Component.literal("Welcome Screen")) {

    companion object {
        private const val SITE_URL = "https://rdbt.top/"
        private const val GUIDE_URL = "https://rdbt.top/docs/ratted"
        private const val REQUIRED_TEXT = "I UNDERSTAND"
        private const val TITLE_SCALE = 3.0f
        private const val TARGET_SCALE = 2.0f
        private const val LINE_HEIGHT = 10f

        private const val TITLE_HEIGHT = 50
        private const val TEXT_LINES = 11
        private const val GAP_AFTER_TEXT = 15
        private const val INPUT_HEIGHT = 20
        private const val GAP_AFTER_INPUT = 10
        private const val BUTTON_HEIGHT = 20

        @JvmStatic
        fun open() {
            val client = Minecraft.getInstance()
            client.execute { client.gui.setScreen(WelcomeScreen()) }
        }
    }

    private val forcedMultiplier = calculateForcedMultiplier()
    private lateinit var inputBox: EditBox
    private lateinit var closeButton: Button
    private var contentOffsetY = 0f

    private fun calculateForcedMultiplier(): Float {
        val currentScale = Minecraft.getInstance().window.guiScale.toFloat()
        return if (currentScale < TARGET_SCALE) TARGET_SCALE / currentScale else 1.0f
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun init() {
        super.init()

        val virtualHeight = (height / forcedMultiplier).toInt()

        val textHeight = (TEXT_LINES * LINE_HEIGHT).toInt()
        val totalContentHeight = TITLE_HEIGHT + textHeight + GAP_AFTER_TEXT + INPUT_HEIGHT + GAP_AFTER_INPUT + BUTTON_HEIGHT

        contentOffsetY = (virtualHeight - totalContentHeight) / 2f

        val inputY = (contentOffsetY + TITLE_HEIGHT + textHeight + GAP_AFTER_TEXT).toInt()
        val buttonY = inputY + INPUT_HEIGHT + GAP_AFTER_INPUT

        setupInputBox(inputY)
        setupButtons(buttonY)
    }

    private fun setupInputBox(y: Int) {
        inputBox = EditBox(font, -100, y, 200, 20, Component.literal("Verify"))
        inputBox.setResponder { text ->
            closeButton.active = text.equals(REQUIRED_TEXT, ignoreCase = true)
        }
        addRenderableWidget(inputBox)
    }

    private fun setupButtons(y: Int) {
        addRenderableWidget(
            Button.builder(Component.literal("Read the Guide")) { _ ->
                confirmOpenUrl(GUIDE_URL)
            }.bounds(-105, y, 100, 20).build()
        )

        closeButton = Button.builder(Component.literal("Close")) { _ ->
            Config.markWelcomeShown()
            onClose()
        }.bounds(5, y, 100, 20).build()
        closeButton.active = false
        addRenderableWidget(closeButton)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val matrices = context.pose()

        matrices.pushMatrix()
        matrices.translate(width / 2f, 0f)
        matrices.scale(forcedMultiplier, forcedMultiplier)

        renderTitle(context)
        renderWarningText(context)

        val (scaledMouseX, scaledMouseY) = scaled(mouseX.toDouble(), mouseY.toDouble())

        super.extractRenderState(context, scaledMouseX.toInt(), scaledMouseY.toInt(), delta)
        matrices.popMatrix()
    }

    private fun renderTitle(context: GuiGraphicsExtractor) {
        val matrices = context.pose()

        matrices.pushMatrix()
        matrices.scale(TITLE_SCALE, TITLE_SCALE)

        val titleText = "Welcome to V5"
        val totalWidth = font.width(titleText)
        var currentX = -totalWidth / 2f
        val yPos = ((contentOffsetY + 10) / TITLE_SCALE).toInt()

        for (char in titleText) {
            val charStr = char.toString()
            val hue = ((System.currentTimeMillis() + (currentX * 10).toLong()) % 2000) / 2000f
            val color = Color.getHSBColor(hue, 0.9f, 1f).rgb
            context.text(font, charStr, currentX.toInt(), yPos, color, true)
            currentX += font.width(charStr)
        }

        matrices.popMatrix()
    }

    private fun renderWarningText(context: GuiGraphicsExtractor) {
        val lines = arrayOf(
            Component.literal("⚠ THE OFFICIAL V5 WEBSITE IS: ").withStyle(ChatFormatting.RED)
                .append(Component.literal(SITE_URL).withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE))
                .append(Component.literal(" ⚠").withStyle(ChatFormatting.RED)),
            Component.literal("⚠ IF YOU DOWNLOADED THIS FROM ANYWHERE ELSE, IT MAY BE A VIRUS! ⚠").withStyle(
                ChatFormatting.RED),
            Component.empty(),
            Component.literal("BEWARE OF FAKE RELEASES:").withStyle(ChatFormatting.RED),
            Component.literal("If you did not download this from the official Discord,"),
            Component.literal("your account may be at risk!"),
            Component.literal("Click \"Read the Guide\" to learn how to protect your account."),
            Component.empty(),
            Component.literal("TO ACCESS THE CLIENT:").withStyle(ChatFormatting.YELLOW),
            Component.literal("If you understand the risks and are using the official version,"),
            Component.literal("type \"I understand\" in the box below and click Close.")
        )

        var yPosition = contentOffsetY + TITLE_HEIGHT
        for (line in lines) {
            val xPos = -font.width(line) / 2
            context.text(font, line, xPos, yPosition.toInt(), -1, true)
            yPosition += LINE_HEIGHT
        }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val (scaledX, scaledY) = scaled(click.x, click.y)
        val scaledClick = MouseButtonEvent(scaledX, scaledY, click.buttonInfo())

        if (handleSiteLinkClick(scaledX, scaledY)) return true

        updateInputBoxFocus(scaledX, scaledY)
        return super.mouseClicked(scaledClick, doubled)
    }

    private fun handleSiteLinkClick(scaledX: Double, scaledY: Double): Boolean {
        val prefix = "⚠ THE OFFICIAL V5 WEBSITE IS: "
        val fullLine = "$prefix$SITE_URL ⚠"
        val fullWidth = font.width(fullLine)
        val prefixWidth = font.width(prefix)
        val linkWidth = font.width(SITE_URL)

        val linkStartX = (-fullWidth / 2.0) + prefixWidth
        val linkEndX = linkStartX + linkWidth
        val linkYStart = (contentOffsetY + TITLE_HEIGHT).toDouble()
        val linkYEnd = linkYStart + LINE_HEIGHT

        if (scaledX in linkStartX..linkEndX && scaledY in linkYStart..linkYEnd) {
            confirmOpenUrl(SITE_URL)
            return true
        }
        return false
    }

    private fun updateInputBoxFocus(scaledX: Double, scaledY: Double) {
        inputBox.isFocused = inputBox.isMouseOver(scaledX, scaledY)
        if (inputBox.isFocused) focused = inputBox
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val (scaledX, scaledY) = scaled(click.x, click.y)
        val scaledClick = MouseButtonEvent(scaledX, scaledY, click.buttonInfo())
        return super.mouseReleased(scaledClick)
    }

    private fun scaled(x: Double, y: Double) =
        (x - width / 2.0) / forcedMultiplier to y / forcedMultiplier

    private fun confirmOpenUrl(url: String) {
        minecraft.gui.setScreen(ConfirmLinkScreen({ confirmed ->
            if (confirmed) Util.getPlatform().openUri(url)
            minecraft.gui.setScreen(this)
        }, url, true))
    }

}
