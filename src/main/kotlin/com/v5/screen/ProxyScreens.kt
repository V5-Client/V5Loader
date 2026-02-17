package com.v5.screen

import com.v5.proxy.Proxy
import com.v5.proxy.ProxyInfo
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Element
import net.minecraft.client.gui.Selectable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.ElementListWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting

class ProxyManagerScreen(private val parent: Screen) : Screen(Text.literal("Proxy Manager")) {
    private lateinit var proxyList: ProxyListWidget

    override fun init() {
        proxyList = ProxyListWidget(client!!, width, height - 64, 32, 24)
        addDrawableChild(proxyList)

        addDrawableChild(ButtonWidget.builder(Text.literal("Add Proxy")) {
            client?.setScreen(ProxyEditScreen(this, null))
        }.dimensions(width / 2 - 102, height - 28, 100, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Back")) {
            client?.setScreen(parent)
        }.dimensions(width / 2 + 2, height - 28, 100, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF.toInt())
    }

    fun refreshList() {
        proxyList.refresh()
    }

    inner class ProxyListWidget(
        client: MinecraftClient, width: Int, height: Int, y: Int, itemHeight: Int
    ) : ElementListWidget<ProxyListWidget.ProxyEntry>(client, width, height, y, itemHeight) {

        init {
            refresh()
        }

        override fun getRowWidth(): Int {
            return width - 560
        }

        fun refresh() {
            clearEntries()
            ProxyInfo.getProxies().forEach { proxy ->
                addEntry(ProxyEntry(proxy))
            }
        }

        inner class ProxyEntry(private val proxy: Proxy) : Entry<ProxyEntry>() {
            private val editBtn: ButtonWidget
            private val deleteBtn: ButtonWidget
            private val toggleBtn: ButtonWidget

            init {
                toggleBtn = ButtonWidget.builder(getToggleText()) {
                    ProxyInfo.setProxyEnabled(proxy, !proxy.isEnabled)
                    refresh()
                }.dimensions(0, 0, 50, 20).build()

                editBtn = ButtonWidget.builder(Text.literal("Edit")) {
                    client?.setScreen(ProxyEditScreen(this@ProxyManagerScreen, proxy))
                }.dimensions(0, 0, 40, 20).build()

                deleteBtn = ButtonWidget.builder(Text.literal("X").formatted(Formatting.RED)) {
                    ProxyInfo.removeProxy(proxy)
                    refresh()
                }.dimensions(0, 0, 20, 20).build()
            }

            private fun getToggleText(): Text =
                if (proxy.isEnabled) Text.literal("ON").formatted(Formatting.GREEN)
                else Text.literal("OFF").formatted(Formatting.RED)

            override fun render(
                context: DrawContext,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                deltaTicks: Float
            ) {
                val entryIndex = this@ProxyListWidget.children().indexOf(this)
                val y = this@ProxyListWidget.getRowTop(entryIndex)
                val x = this@ProxyListWidget.rowLeft
                val entryWidth = this@ProxyListWidget.rowWidth
                val tr = this@ProxyListWidget.client.textRenderer

                val textY = y + (24 - 9) / 2

                val nameText = if (proxy.name.isNotBlank()) {
                    "${proxy.name} (${proxy.ip}:${proxy.port})"
                } else {
                    "${proxy.ip}:${proxy.port}"
                }

                val buttonsWidth = 120

                val maxTextWidth = entryWidth - buttonsWidth - 10

                val trimmedText = if (tr.getWidth(nameText) > maxTextWidth) {
                    tr.trimToWidth(nameText, maxTextWidth)
                } else {
                    nameText
                }

                context.drawTextWithShadow(tr, Text.literal(trimmedText), x + 2, textY, 0xFFFFFFFF.toInt())

                toggleBtn.setPosition(x + entryWidth - 118, y)
                toggleBtn.message = getToggleText()
                toggleBtn.render(context, mouseX, mouseY, deltaTicks)

                editBtn.setPosition(x + entryWidth - 65, y)
                editBtn.render(context, mouseX, mouseY, deltaTicks)

                deleteBtn.setPosition(x + entryWidth - 22, y)
                deleteBtn.render(context, mouseX, mouseY, deltaTicks)
            }

            override fun children(): MutableList<out Element> =
                mutableListOf(toggleBtn, editBtn, deleteBtn)

            override fun selectableChildren(): MutableList<out Selectable> =
                mutableListOf(toggleBtn, editBtn, deleteBtn)
        }
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

    private val spacing = 45

    override fun init() {
        val centerX = width / 2
        val startY = 45

        nameField = createField(centerX, startY, "My Proxy", existingProxy?.name)
        ipField = createField(centerX, startY + spacing, "127.0.0.1", existingProxy?.ip)
        portField = createField(centerX, startY + spacing * 2, "1080", existingProxy?.port?.toString())
        usernameField = createField(centerX, startY + spacing * 3, "Optional", existingProxy?.username)
        passwordField = createField(centerX, startY + spacing * 4, "Optional", existingProxy?.password)

        addDrawableChild(ButtonWidget.builder(Text.literal("Save")) {
            save()
        }.dimensions(centerX - 105, height - 40, 100, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel")) {
            client?.setScreen(parent)
        }.dimensions(centerX + 5, height - 40, 100, 20).build())
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

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF.toInt())

        val startX = width / 2 - 100
        val startY = 45 - 11

        val labelColor = 0xFFAAAAAA.toInt()
        context.drawTextWithShadow(textRenderer, Text.literal("Name"), startX, startY, labelColor)
        context.drawTextWithShadow(textRenderer, Text.literal("IP Address"), startX, startY + spacing, labelColor)
        context.drawTextWithShadow(textRenderer, Text.literal("Port"), startX, startY + spacing * 2, labelColor)
        context.drawTextWithShadow(textRenderer, Text.literal("Username"), startX, startY + spacing * 3, labelColor)
        context.drawTextWithShadow(textRenderer, Text.literal("Password"), startX, startY + spacing * 4, labelColor)
    }
}