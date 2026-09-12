package com.chattriggers.ctjs.api.client

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class ProxyManagerScreen(private val parent: Screen) : Screen(Component.literal("Proxy Manager")) {
    private val rowHeight = 24
    private val listTop = 40
    private val listWidth = 340
    private var scrollOffset = 0f
    private var proxies = mutableListOf<Proxy>()

    private lateinit var addButton: Button
    private lateinit var backButton: Button
    private val rowButtons = mutableListOf<RowButtons>()

    private data class RowButtons(
        val toggle: Button,
        val edit: Button,
        val delete: Button
    )

    override fun init() {
        super.init()
        refreshList()

        val buttonY = height - 28
        addButton = addRenderableWidget(
            Button.builder(Component.literal("Add Proxy")) {
                minecraft.setScreenCompat(ProxyEditScreen(this, null))
            }.bounds(width / 2 - 102, buttonY, 100, 20).build()
        )
        backButton = addRenderableWidget(
            Button.builder(Component.literal("Back")) {
                minecraft.setScreenCompat(parent)
            }.bounds(width / 2 + 2, buttonY, 100, 20).build()
        )
        syncRowButtons()
    }

    override fun onClose() {
        minecraft.setScreenCompat(parent)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val listX = width / 2 - 170
        val listHeight = height - 76
        val panelWidth = listWidth + 8
        val contentTop = listTop + 6f
        val contentBottom = listTop + listHeight - 6f
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(0f)
        clampScroll(contentHeight)
        layoutRowButtons(contentTop, contentBottom)

        context.fill(listX, listTop, listX + panelWidth, listTop + listHeight, ScreenHelper.argb(82, 24, 31, 44))
        context.fill(listX, listTop, listX + panelWidth, listTop + 1, ScreenHelper.argb(70, 200, 214, 230))
        context.fill(listX, listTop + listHeight - 1, listX + panelWidth, listTop + listHeight, ScreenHelper.argb(70, 200, 214, 230))
        context.fill(listX, listTop, listX + 1, listTop + listHeight, ScreenHelper.argb(70, 200, 214, 230))
        context.fill(listX + panelWidth - 1, listTop, listX + panelWidth, listTop + listHeight, ScreenHelper.argb(70, 200, 214, 230))

        context.text(font, title.string, width / 2 - font.width(title.string) / 2, 16, 0xFFFFFF, false)

        if (proxies.isEmpty()) {
            val message = "No proxies added yet"
            context.text(
                font,
                message,
                width / 2 - font.width(message) / 2,
                (contentTop + contentHeight / 2f).toInt(),
                ScreenHelper.argb(230, 203, 213, 224),
                false
            )
        } else {
            proxies.forEachIndexed { index, proxy ->
                drawProxyRow(context, proxy, index, listX, contentTop, contentBottom)
            }
            drawScrollbar(context, listX, contentTop, contentHeight)
        }

        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    private fun drawProxyRow(
        context: GuiGraphicsExtractor,
        proxy: Proxy,
        index: Int,
        listX: Int,
        contentTop: Float,
        contentBottom: Float
    ) {
        val rowY = (contentTop - scrollOffset + index * rowHeight).toInt()
        val rowInnerHeight = rowHeight - 2
        if (rowY > contentBottom || rowY + rowInnerHeight < contentTop) return

        context.fill(
            listX + 4,
            rowY,
            listX + listWidth - 4,
            rowY + rowInnerHeight,
            ScreenHelper.argb(78, 38, 50, 67)
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
        val buttonsTotal = toggleW + editW + deleteW + gap * 2
        val textMax = listWidth - buttonsTotal - 24
        val label = trimToWidth(entryLabel, textMax)

        context.text(font, label, listX + 11, rowY + 8, 0xFFFFFF, false)
    }

    private fun drawScrollbar(context: GuiGraphicsExtractor, listX: Int, contentTop: Float, contentHeight: Float) {
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

        context.fill(trackX.toInt(), trackY.toInt(), (trackX + trackWidth).toInt(), (trackY + trackHeight).toInt(), ScreenHelper.argb(80, 185, 199, 214))
        context.fill(trackX.toInt(), thumbY.toInt(), (trackX + trackWidth).toInt(), (thumbY + thumbHeight).toInt(), ScreenHelper.argb(200, 234, 242, 250))
    }

    private fun syncRowButtons() {
        rowButtons.forEach {
            removeWidget(it.toggle)
            removeWidget(it.edit)
            removeWidget(it.delete)
        }
        rowButtons.clear()

        proxies.forEach { proxy ->
            val toggle = addRenderableWidget(
                Button.builder(Component.literal(if (proxy.isEnabled) "ON" else "OFF")) {
                    ProxyInfo.setProxyEnabled(proxy, !proxy.isEnabled)
                    refreshList()
                }.bounds(0, 0, 50, 20).build()
            )
            val edit = addRenderableWidget(
                Button.builder(Component.literal("Edit")) {
                    minecraft.setScreenCompat(ProxyEditScreen(this, proxy))
                }.bounds(0, 0, 40, 20).build()
            )
            val delete = addRenderableWidget(
                Button.builder(Component.literal("X")) {
                    ProxyInfo.removeProxy(proxy)
                    refreshList()
                }.bounds(0, 0, 22, 20).build()
            )
            rowButtons += RowButtons(toggle, edit, delete)
        }
    }

    private fun layoutRowButtons(contentTop: Float, contentBottom: Float) {
        val listX = width / 2 - 170
        val toggleW = 50
        val editW = 40
        val gap = 3
        val rightPad = 7
        val buttonsTotal = toggleW + editW + 22 + gap * 2

        rowButtons.forEachIndexed { index, buttons ->
            val rowY = (contentTop - scrollOffset + index * rowHeight).toInt()
            val rowInnerHeight = rowHeight - 2
            val visible = rowY <= contentBottom && rowY + rowInnerHeight >= contentTop
            val toggleX = listX + listWidth - rightPad - buttonsTotal
            val editX = toggleX + toggleW + gap
            val deleteX = editX + editW + gap
            val btnY = rowY + 1

            buttons.toggle.visible = visible
            buttons.edit.visible = visible
            buttons.delete.visible = visible
            buttons.toggle.setX(toggleX)
            buttons.edit.setX(editX)
            buttons.delete.setX(deleteX)
            buttons.toggle.setY(btnY)
            buttons.edit.setY(btnY)
            buttons.delete.setY(btnY)
        }
    }

    private fun trimToWidth(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "..."
        val textWidth = maxWidth - font.width(ellipsis)
        return if (textWidth <= 0) ellipsis else font.plainSubstrByWidth(text, textWidth) + ellipsis
    }

    fun refreshList() {
        proxies = ProxyInfo.getProxies().toMutableList()
        val listHeight = height - 76
        val contentHeight = (listHeight - 12f).coerceAtLeast(0f)
        clampScroll(contentHeight)
        if (::addButton.isInitialized) syncRowButtons()
    }

    private fun getMaxScroll(contentHeight: Float): Float {
        val totalContentHeight = proxies.size * rowHeight.toFloat()
        return (totalContentHeight - contentHeight).coerceAtLeast(0f)
    }

    private fun clampScroll(contentHeight: Float) {
        scrollOffset = scrollOffset.coerceIn(0f, getMaxScroll(contentHeight))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mouseX = click.x.toInt()
        val mouseY = click.y.toInt()

        val listX = width / 2 - 170
        val listHeight = height - 76
        val contentTop = listTop + 6
        val contentHeight = (listHeight - 12f).coerceAtLeast(0f)

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
) : Screen(Component.literal(if (existingProxy == null) "Add Proxy" else "Edit Proxy")) {

    private lateinit var nameField: EditBox
    private lateinit var ipField: EditBox
    private lateinit var portField: EditBox
    private lateinit var usernameField: EditBox
    private lateinit var passwordField: EditBox

    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    private val spacing = 45

    override fun init() {
        super.init()
        val centerX = width / 2
        val startY = 45

        nameField = createField(centerX, startY, "My Proxy", existingProxy?.name)
        ipField = createField(centerX, startY + spacing, "127.0.0.1", existingProxy?.ip)
        portField = createField(centerX, startY + spacing * 2, "1080", existingProxy?.port?.toString())
        usernameField = createField(centerX, startY + spacing * 3, "Optional", existingProxy?.username)
        passwordField = createField(centerX, startY + spacing * 4, "Optional", existingProxy?.password)

        saveButton = addRenderableWidget(
            Button.builder(Component.literal("Save")) {
                save()
            }.bounds(centerX - 105, height - 40, 100, 20).build()
        )
        cancelButton = addRenderableWidget(
            Button.builder(Component.literal("Cancel")) {
                minecraft.setScreenCompat(parent)
            }.bounds(centerX + 5, height - 40, 100, 20).build()
        )
    }

    override fun onClose() {
        minecraft.setScreenCompat(parent)
    }

    private fun createField(centerX: Int, y: Int, placeholder: String, value: String?): EditBox {
        val field = EditBox(font, centerX - 100, y, 200, 20, Component.literal(""))
        field.setValue(value ?: "")
        field.setHint(Component.literal(placeholder).withStyle(ChatFormatting.GRAY))
        addRenderableWidget(field)
        return field
    }

    private fun save() {
        val name = nameField.value.trim()
        val ip = ipField.value.trim()
        val portStr = portField.value.trim()
        val username = usernameField.value.trim()
        val password = passwordField.value.trim()

        if (ip.isBlank() || portStr.isBlank()) return
        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return

        val newProxy = Proxy(ip, port, name.ifBlank { ip }, username, password, existingProxy?.isEnabled ?: false)

        if (existingProxy != null) {
            ProxyInfo.updateProxy(existingProxy, newProxy)
        } else {
            ProxyInfo.addProxy(newProxy)
        }

        parent.refreshList()
        minecraft.setScreenCompat(parent)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val startX = width / 2 - 100
        val startY = 45 - 11
        val labelColor = ScreenHelper.argb(255, 170, 170, 170)

        context.text(font, title.string, width / 2 - font.width(title.string) / 2, 15, 0xFFFFFF, false)
        context.text(font, "Name", startX, startY, labelColor, false)
        context.text(font, "IP Address", startX, startY + spacing, labelColor, false)
        context.text(font, "Port", startX, startY + spacing * 2, labelColor, false)
        context.text(font, "Username", startX, startY + spacing * 3, labelColor, false)
        context.text(font, "Password", startX, startY + spacing * 4, labelColor, false)
        context.text(
            font,
            "Logged in as ${minecraft.user.name}",
            8,
            height - 12,
            ScreenHelper.argb(225, 221, 232, 242),
            false
        )

        super.extractRenderState(context, mouseX, mouseY, delta)
    }
}
