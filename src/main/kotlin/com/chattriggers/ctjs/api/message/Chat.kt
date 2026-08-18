package com.chattriggers.ctjs.api.message

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.ChatFormatting
import net.minecraft.util.ARGB

object Chat {

    private val mc = Minecraft.getInstance()
    @JvmStatic
    fun sendGradientMsg(prefix: String, startRgb: Int, endRgb: Int, vararg messages: Any) {
        val finalMessage = Component.empty()

        if (prefix.length <= 1) {
            finalMessage.append(Component.literal(prefix).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(startRgb))))
        } else {
            prefix.forEachIndexed { index, char ->
                val rgb = ARGB.srgbLerp(index.toFloat() / (prefix.length - 1), startRgb, endRgb)
                finalMessage.append(
                    Component.literal(char.toString()).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
                )
            }
        }
        finalMessage.append(Component.literal(" ").withStyle(ChatFormatting.RESET))

        for (part in messages) {
            val partText: Component = when (part) {
                is Component -> part
                is String -> TextComponent(part)
                else -> Component.literal(part.toString())
            }
            finalMessage.append(partText)
        }

        mc.gui.hud.chat.addClientSystemMessage(finalMessage)
    }
}
