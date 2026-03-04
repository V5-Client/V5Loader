package com.v5.screen

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ConfirmLinkScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Util
import java.awt.Color

class WelcomeScreen : Screen(Text.literal("Welcome Screen")) {

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
            val client = MinecraftClient.getInstance()
            client.execute { client.setScreen(WelcomeScreen()) }
        }
    }

    private val forcedMultiplier = calculateForcedMultiplier()
    private lateinit var inputBox: TextFieldWidget
    private lateinit var closeButton: ButtonWidget
    private var contentOffsetY = 0f

    private fun calculateForcedMultiplier(): Float {
        val currentScale = MinecraftClient.getInstance().window.scaleFactor.toFloat()
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
        inputBox = TextFieldWidget(textRenderer, -100, y, 200, 20, Text.literal("Verify"))
        inputBox.setChangedListener { text ->
            closeButton.active = text.equals(REQUIRED_TEXT, ignoreCase = true)
        }
        addDrawableChild(inputBox)
    }

    private fun setupButtons(y: Int) {
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Read the Guide")) { _ ->
                client?.setScreen(ConfirmLinkScreen({ confirmed ->
                    if (confirmed) Util.getOperatingSystem().open(GUIDE_URL)
                    client?.setScreen(this)
                }, GUIDE_URL, true))
            }.dimensions(-105, y, 100, 20).build()
        )

        closeButton = ButtonWidget.builder(Text.literal("Close")) { _ ->
            if (inputBox.text.equals(REQUIRED_TEXT, ignoreCase = true)) close()
        }.dimensions(5, y, 100, 20).build()
        closeButton.active = false
        addDrawableChild(closeButton)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val matrices = context.matrices

        matrices.pushMatrix()
        matrices.translate(width / 2f, 0f)
        matrices.scale(forcedMultiplier, forcedMultiplier)

        renderTitle(context)
        renderWarningText(context)

        val scaledMouseX = ((mouseX - (width / 2f)) / forcedMultiplier).toInt()
        val scaledMouseY = (mouseY / forcedMultiplier).toInt()

        super.render(context, scaledMouseX, scaledMouseY, delta)
        matrices.popMatrix()
    }

    private fun renderTitle(context: DrawContext) {
        val matrices = context.matrices

        matrices.pushMatrix()
        matrices.scale(TITLE_SCALE, TITLE_SCALE)

        val titleText = "Welcome to V5"
        val totalWidth = textRenderer.getWidth(titleText)
        var currentX = -totalWidth / 2f
        val yPos = ((contentOffsetY + 10) / TITLE_SCALE).toInt()

        for (char in titleText) {
            val charStr = char.toString()
            val hue = ((System.currentTimeMillis() + (currentX * 10).toLong()) % 2000) / 2000f
            val color = Color.getHSBColor(hue, 0.9f, 1f).rgb
            context.drawText(textRenderer, charStr, currentX.toInt(), yPos, color, true)
            currentX += textRenderer.getWidth(charStr)
        }

        matrices.popMatrix()
    }

    private fun renderWarningText(context: DrawContext) {
        val lines = arrayOf(
            Text.literal("⚠ THE OFFICIAL V5 WEBSITE IS: ").formatted(Formatting.RED)
                .append(Text.literal(SITE_URL).formatted(Formatting.BLUE, Formatting.UNDERLINE))
                .append(Text.literal(" ⚠").formatted(Formatting.RED)),
            Text.literal("⚠ IF YOU DOWNLOADED THIS FROM ANYWHERE ELSE, IT MAY BE A VIRUS! ⚠").formatted(Formatting.RED),
            Text.empty(),
            Text.literal("BEWARE OF FAKE RELEASES:").formatted(Formatting.RED),
            Text.literal("If you did not download this from the official Discord,"),
            Text.literal("your account may be at risk!"),
            Text.literal("Click \"Read the Guide\" to learn how to protect your account."),
            Text.empty(),
            Text.literal("TO ACCESS THE CLIENT:").formatted(Formatting.YELLOW),
            Text.literal("If you understand the risks and are using the official version,"),
            Text.literal("type \"I understand\" in the box below and click Close.")
        )

        var yPosition = contentOffsetY + TITLE_HEIGHT
        for (line in lines) {
            val xPos = -textRenderer.getWidth(line) / 2
            context.drawText(textRenderer, line, xPos, yPosition.toInt(), -1, true)
            yPosition += LINE_HEIGHT
        }
    }

    override fun mouseClicked(click: net.minecraft.client.gui.Click, doubled: Boolean): Boolean {
        val scaledX = (click.x - (width / 2.0)) / forcedMultiplier
        val scaledY = click.y / forcedMultiplier
        val scaledClick = net.minecraft.client.gui.Click(scaledX, scaledY, click.buttonInfo())

        if (handleSiteLinkClick(scaledX, scaledY)) return true

        updateInputBoxFocus(scaledX, scaledY)
        return super.mouseClicked(scaledClick, doubled)
    }

    private fun handleSiteLinkClick(scaledX: Double, scaledY: Double): Boolean {
        val prefix = "⚠ THE OFFICIAL V5 WEBSITE IS: "
        val fullLine = "$prefix$SITE_URL ⚠"
        val fullWidth = textRenderer.getWidth(fullLine)
        val prefixWidth = textRenderer.getWidth(prefix)
        val linkWidth = textRenderer.getWidth(SITE_URL)

        val linkStartX = (-fullWidth / 2.0) + prefixWidth
        val linkEndX = linkStartX + linkWidth
        val linkYStart = (contentOffsetY + TITLE_HEIGHT).toDouble()
        val linkYEnd = linkYStart + LINE_HEIGHT

        if (scaledX in linkStartX..linkEndX && scaledY in linkYStart..linkYEnd) {
            client?.setScreen(ConfirmLinkScreen({ confirmed ->
                if (confirmed) Util.getOperatingSystem().open(SITE_URL)
                client?.setScreen(this)
            }, SITE_URL, true))
            return true
        }
        return false
    }

    private fun updateInputBoxFocus(scaledX: Double, scaledY: Double) {
        inputBox.isFocused = inputBox.isMouseOver(scaledX, scaledY)
        if (inputBox.isFocused) focused = inputBox
    }

    override fun mouseReleased(click: net.minecraft.client.gui.Click): Boolean {
        val scaledX = (click.x - (width / 2.0)) / forcedMultiplier
        val scaledY = click.y / forcedMultiplier
        val scaledClick = net.minecraft.client.gui.Click(scaledX, scaledY, click.buttonInfo())
        return super.mouseReleased(scaledClick)
    }

}