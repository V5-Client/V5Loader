package com.v5.screen

import com.v5.proxy.Proxy
import com.v5.proxy.ProxyInfo
import com.v5.render.NVGRenderer
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_BOTTOM
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_TOP

class ProxyManagerScreen(private val parent: Screen) : Screen(Text.literal("Proxy Manager")) {
    private val rowHeight = 24
    private val listTop = 40
    private val listWidth = 340
    private var scrollOffset = 0f
    private var proxies = mutableListOf<Proxy>()

    private lateinit var addButton: ScreenHelper.MenuButton
    private lateinit var backButton: ScreenHelper.MenuButton

    override fun init() {
        refreshList()

        val buttonY = height - 28
        addButton = ScreenHelper.MenuButton("Add Proxy", width / 2 - 102, buttonY, 100, 20) {
            client?.setScreen(ProxyEditScreen(this, null))
        }
        backButton = ScreenHelper.MenuButton("Back", width / 2 + 2, buttonY, 100, 20) {
            client?.setScreen(parent)
        }
    }

    override fun close() {
        client?.setScreen(parent)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderPanoramaBackground(context, delta)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)

        val listX = width / 2 - 170
        val listHeight = height - 76
        val panelWidth = listWidth + 8
        val contentTop = listTop + 6f
        val contentBottom = listTop + listHeight - 6f
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(0f)
        clampScroll(contentHeight)

        NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
        try {
            NVGRenderer.drawRoundedRect(
                listX.toFloat(),
                listTop.toFloat(),
                panelWidth.toFloat(),
                listHeight.toFloat(),
                6f,
                ScreenHelper.argb(82, 24, 31, 44)
            )
            NVGRenderer.drawHollowRect(
                listX.toFloat(),
                listTop.toFloat(),
                panelWidth.toFloat(),
                listHeight.toFloat(),
                0.8f,
                ScreenHelper.argb(70, 200, 214, 230),
                6f
            )

            NVGRenderer.text(
                title.string,
                width / 2f,
                16f,
                14f,
                0xFFFFFFFF.toInt(),
                ScreenHelper.titleFont,
                NVG_ALIGN_CENTER or NVG_ALIGN_TOP
            )

            if (proxies.isEmpty()) {
                NVGRenderer.text(
                    "No proxies added yet",
                    width / 2f,
                    contentTop + contentHeight / 2f,
                    9f,
                    ScreenHelper.argb(230, 203, 213, 224),
                    ScreenHelper.smallerFont,
                    NVG_ALIGN_CENTER or NVG_ALIGN_MIDDLE
                )
            } else {
                NVGRenderer.pushScissor(listX + 4f, contentTop, listWidth - 4f, contentHeight)
                proxies.forEachIndexed { index, proxy ->
                    drawProxyRow(proxy, index, mouseX, mouseY, listX, contentTop, contentBottom)
                }
                NVGRenderer.popScissor()
                drawScrollbar(listX, contentTop, contentHeight)
            }

            ScreenHelper.drawMenuButton(
                addButton.label,
                addButton.x.toFloat(),
                addButton.y.toFloat(),
                addButton.width.toFloat(),
                addButton.height.toFloat(),
                addButton.isHovered(mouseX, mouseY),
                null
            )
            ScreenHelper.drawMenuButton(
                backButton.label,
                backButton.x.toFloat(),
                backButton.y.toFloat(),
                backButton.width.toFloat(),
                backButton.height.toFloat(),
                backButton.isHovered(mouseX, mouseY),
                null
            )
        } finally {
            NVGRenderer.endFrame()
        }
    }

    private fun drawProxyRow(
        proxy: Proxy,
        index: Int,
        mouseX: Int,
        mouseY: Int,
        listX: Int,
        contentTop: Float,
        contentBottom: Float
    ) {
        val rowY = (contentTop - scrollOffset + index * rowHeight).toInt()
        val rowInnerHeight = rowHeight - 2
        val hovered = mouseX in listX until (listX + listWidth) && mouseY in rowY until (rowY + rowInnerHeight)
        if (rowY > contentBottom || rowY + rowInnerHeight < contentTop) return

        NVGRenderer.drawRoundedRect(
            listX.toFloat() + 4f,
            rowY.toFloat(),
            listWidth.toFloat() - 8f,
            rowInnerHeight.toFloat(),
            4f,
            if (hovered) ScreenHelper.argb(95, 47, 61, 82) else ScreenHelper.argb(78, 38, 50, 67)
        )

        val entryLabel = if (proxy.name.isNotBlank()) {
            "${proxy.name} (${proxy.ip}:${proxy.port})"
        } else {
            "${proxy.ip}:${proxy.port}"
        }

        val toggleW = 50
        val editW = 40
        val deleteW = 22
        val gap = 3
        val rightPad = 7
        val buttonsTotal = toggleW + editW + deleteW + gap * 2
        val textMax = listWidth - buttonsTotal - 24
        val label = trimToWidth(entryLabel, textMax.toFloat(), 8.6f)

        NVGRenderer.text(
            label,
            listX + 11f,
            rowY + rowInnerHeight / 2f,
            8.6f,
            0xFFFFFFFF.toInt(),
            ScreenHelper.smallerFont,
            NVG_ALIGN_LEFT or NVG_ALIGN_MIDDLE
        )

        val toggleX = listX + listWidth - rightPad - buttonsTotal
        val editX = toggleX + toggleW + gap
        val deleteX = editX + editW + gap
        val btnY = rowY + 1
        val btnH = rowInnerHeight - 2

        ScreenHelper.drawMenuButton(
            if (proxy.isEnabled) "ON" else "OFF",
            toggleX.toFloat(),
            btnY.toFloat(),
            toggleW.toFloat(),
            btnH.toFloat(),
            mouseX in toggleX until (toggleX + toggleW) && mouseY in btnY until (btnY + btnH),
            if (proxy.isEnabled) ScreenHelper.argb(255, 110, 255, 110) else ScreenHelper.argb(255, 255, 110, 110)
        )
        ScreenHelper.drawMenuButton(
            "Edit",
            editX.toFloat(),
            btnY.toFloat(),
            editW.toFloat(),
            btnH.toFloat(),
            mouseX in editX until (editX + editW) && mouseY in btnY until (btnY + btnH),
            null
        )
        ScreenHelper.drawMenuButton(
            "X",
            deleteX.toFloat(),
            btnY.toFloat(),
            deleteW.toFloat(),
            btnH.toFloat(),
            mouseX in deleteX until (deleteX + deleteW) && mouseY in btnY until (btnY + btnH),
            null
        )
    }

    private fun drawScrollbar(listX: Int, contentTop: Float, contentHeight: Float) {
        val maxScroll = getMaxScroll(contentHeight)
        if (maxScroll <= 0f) return

        val trackWidth = 3f
        val trackX = listX + listWidth + 2f
        val trackY = contentTop
        val trackHeight = contentHeight

        val totalContentHeight = proxies.size * rowHeight.toFloat()
        val thumbHeight = (trackHeight * (contentHeight / totalContentHeight)).coerceIn(18f, trackHeight)
        val thumbTravel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        val thumbY = trackY + (scrollOffset / maxScroll) * thumbTravel

        NVGRenderer.drawRoundedRect(trackX, trackY, trackWidth, trackHeight, 2f, ScreenHelper.argb(80, 185, 199, 214))
        NVGRenderer.drawRoundedRect(trackX, thumbY, trackWidth, thumbHeight, 2f, ScreenHelper.argb(200, 234, 242, 250))
    }

    private fun trimToWidth(text: String, maxWidth: Float, size: Float): String {
        if (NVGRenderer.textWidth(text, size, ScreenHelper.smallerFont) <= maxWidth) return text
        val ellipsis = "..."
        var candidate = text
        while (candidate.isNotEmpty()) {
            candidate = candidate.dropLast(1)
            val probe = candidate + ellipsis
            if (NVGRenderer.textWidth(probe, size, ScreenHelper.smallerFont) <= maxWidth) {
                return probe
            }
        }
        return ellipsis
    }

    fun refreshList() {
        proxies = ProxyInfo.getProxies().toMutableList()
        val listHeight = height - 76
        val contentHeight = (listHeight - 12f).coerceAtLeast(0f)
        clampScroll(contentHeight)
    }

    private fun getMaxScroll(contentHeight: Float): Float {
        val totalContentHeight = proxies.size * rowHeight.toFloat()
        return (totalContentHeight - contentHeight).coerceAtLeast(0f)
    }

    private fun clampScroll(contentHeight: Float) {
        scrollOffset = scrollOffset.coerceIn(0f, getMaxScroll(contentHeight))
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val mouseX = click.x.toInt()
        val mouseY = click.y.toInt()

        if (addButton.isHovered(mouseX, mouseY)) {
            addButton.onClick()
            return true
        }
        if (backButton.isHovered(mouseX, mouseY)) {
            backButton.onClick()
            return true
        }

        val listX = width / 2 - 170
        val listHeight = height - 76
        val contentTop = listTop + 6
        val contentBottom = listTop + listHeight - 6
        val contentHeight = (listHeight - 12f).coerceAtLeast(0f)
        val toggleW = 50
        val editW = 40
        val deleteW = 22
        val gap = 3
        val rightPad = 7
        val buttonsTotal = toggleW + editW + deleteW + gap * 2

        val maxScroll = getMaxScroll(contentHeight)
        if (maxScroll > 0f) {
            val trackWidth = 3f
            val trackX = listX + listWidth + 2f
            val trackY = contentTop.toFloat()
            val trackHeight = contentHeight
            val inTrackX = mouseX >= (trackX - 2f) && mouseX <= (trackX + trackWidth + 2f)
            val inTrackY = mouseY >= trackY && mouseY <= (trackY + trackHeight)
            if (inTrackX && inTrackY) {
                val ratio = ((mouseY - trackY) / trackHeight).coerceIn(0f, 1f)
                scrollOffset = ratio * maxScroll
                return true
            }
        }

        proxies.forEachIndexed { index, proxy ->
            val rowY = (contentTop - scrollOffset + index * rowHeight).toInt()
            val rowInnerHeight = rowHeight - 2
            val toggleX = listX + listWidth - rightPad - buttonsTotal
            val editX = toggleX + toggleW + gap
            val deleteX = editX + editW + gap
            val btnY = rowY + 1
            val btnH = rowInnerHeight - 2
            if (mouseY < contentTop || mouseY > contentBottom) return@forEachIndexed

            when {
                mouseX in toggleX until (toggleX + toggleW) && mouseY in btnY until (btnY + btnH) -> {
                    ProxyInfo.setProxyEnabled(proxy, !proxy.isEnabled)
                    refreshList()
                    return true
                }
                mouseX in editX until (editX + editW) && mouseY in btnY until (btnY + btnH) -> {
                    client?.setScreen(ProxyEditScreen(this, proxy))
                    return true
                }
                mouseX in deleteX until (deleteX + deleteW) && mouseY in btnY until (btnY + btnH) -> {
                    ProxyInfo.removeProxy(proxy)
                    refreshList()
                    return true
                }
            }
        }

        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        delta: Double
    ): Boolean {
        val listX = width / 2 - 170
        val listHeight = height - 76
        val contentTop = listTop + 6f
        val contentHeight = (listHeight - 12f).coerceAtLeast(0f)
        val inList = mouseX >= listX && mouseX <= (listX + listWidth) && mouseY >= contentTop && mouseY <= (contentTop + contentHeight)
        if (!inList) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, delta)

        scrollOffset -= (delta * 14.0).toFloat()
        clampScroll(contentHeight)
        return true
    }
}

class ProxyEditScreen(
    private val parent: ProxyManagerScreen,
    private val existingProxy: Proxy?
) : Screen(Text.literal(if (existingProxy == null) "Add Proxy" else "Edit Proxy")) {

    private lateinit var nameField: TextFieldWidget
    private lateinit var ipField: TextFieldWidget
    private lateinit var portField: TextFieldWidget
    private lateinit var usernameField: TextFieldWidget
    private lateinit var passwordField: TextFieldWidget

    private lateinit var saveButton: ScreenHelper.MenuButton
    private lateinit var cancelButton: ScreenHelper.MenuButton

    private val spacing = 45

    override fun init() {
        val centerX = width / 2
        val startY = 45

        nameField = createField(centerX, startY, "My Proxy", existingProxy?.name)
        ipField = createField(centerX, startY + spacing, "127.0.0.1", existingProxy?.ip)
        portField = createField(centerX, startY + spacing * 2, "1080", existingProxy?.port?.toString())
        usernameField = createField(centerX, startY + spacing * 3, "Optional", existingProxy?.username)
        passwordField = createField(centerX, startY + spacing * 4, "Optional", existingProxy?.password)

        saveButton = ScreenHelper.MenuButton("Save", centerX - 105, height - 40, 100, 20) {
            save()
        }
        cancelButton = ScreenHelper.MenuButton("Cancel", centerX + 5, height - 40, 100, 20) {
            client?.setScreen(parent)
        }
    }

    override fun close() {
        client?.setScreen(parent)
    }

    private fun createField(centerX: Int, y: Int, placeholder: String, value: String?): TextFieldWidget {
        val field = TextFieldWidget(textRenderer, centerX - 100, y, 200, 20, Text.literal(""))
        field.text = value ?: ""
        field.setPlaceholder(Text.literal(placeholder).formatted(Formatting.GRAY))
        addDrawableChild(field)
        return field
    }

    private fun save() {
        val name = nameField.text.trim()
        val ip = ipField.text.trim()
        val portStr = portField.text.trim()
        val username = usernameField.text.trim()
        val password = passwordField.text.trim()

        if (ip.isBlank() || portStr.isBlank()) return
        val port = portStr.toIntOrNull() ?: return

        val newProxy = Proxy(
            ip = ip,
            port = port,
            name = name.ifBlank { ip },
            username = username,
            password = password,
            isEnabled = existingProxy?.isEnabled ?: false
        )

        if (existingProxy != null) {
            ProxyInfo.updateProxy(existingProxy, newProxy)
        } else {
            ProxyInfo.addProxy(newProxy)
        }

        parent.refreshList()
        client?.setScreen(parent)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderPanoramaBackground(context, delta)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        super.render(context, mouseX, mouseY, delta)

        val startX = width / 2 - 100
        val startY = 45 - 11
        val labelColor = ScreenHelper.argb(255, 170, 170, 170)

        NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
        try {
            NVGRenderer.text(
                title.string,
                width / 2f,
                15f,
                14f,
                0xFFFFFFFF.toInt(),
                ScreenHelper.titleFont,
                NVG_ALIGN_CENTER or NVG_ALIGN_TOP
            )

            NVGRenderer.text("Name", startX.toFloat(), startY.toFloat(), 8.7f, labelColor, ScreenHelper.smallerFont, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
            NVGRenderer.text("IP Address", startX.toFloat(), (startY + spacing).toFloat(), 8.7f, labelColor, ScreenHelper.smallerFont, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
            NVGRenderer.text("Port", startX.toFloat(), (startY + spacing * 2).toFloat(), 8.7f, labelColor, ScreenHelper.smallerFont, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
            NVGRenderer.text("Username", startX.toFloat(), (startY + spacing * 3).toFloat(), 8.7f, labelColor, ScreenHelper.smallerFont, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
            NVGRenderer.text("Password", startX.toFloat(), (startY + spacing * 4).toFloat(), 8.7f, labelColor, ScreenHelper.smallerFont, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)

            ScreenHelper.drawMenuButton(
                saveButton.label,
                saveButton.x.toFloat(),
                saveButton.y.toFloat(),
                saveButton.width.toFloat(),
                saveButton.height.toFloat(),
                saveButton.isHovered(mouseX, mouseY),
                null
            )
            ScreenHelper.drawMenuButton(
                cancelButton.label,
                cancelButton.x.toFloat(),
                cancelButton.y.toFloat(),
                cancelButton.width.toFloat(),
                cancelButton.height.toFloat(),
                cancelButton.isHovered(mouseX, mouseY),
                null
            )

            NVGRenderer.text(
                "Logged in as ${client?.session?.username ?: "Unknown"}",
                8f,
                height - 8f,
                8.5f,
                ScreenHelper.argb(225, 221, 232, 242),
                ScreenHelper.smallerFont,
                NVG_ALIGN_LEFT or NVG_ALIGN_BOTTOM
            )
        } finally {
            NVGRenderer.endFrame()
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val mouseX = click.x.toInt()
        val mouseY = click.y.toInt()

        if (saveButton.isHovered(mouseX, mouseY)) {
            saveButton.onClick()
            return true
        }
        if (cancelButton.isHovered(mouseX, mouseY)) {
            cancelButton.onClick()
            return true
        }

        return super.mouseClicked(click, doubled)
    }
}
