package com.chattriggers.ctjs.api.message

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting
import net.minecraft.util.math.ColorHelper

object Chat {

    private val mc = MinecraftClient.getInstance()
    @JvmStatic
    fun sendGradientMsg(prefix: String, startRgb: Int, endRgb: Int, vararg messages: Any) {
        val finalMessage = Text.empty()

        if (prefix.length <= 1) {
            finalMessage.append(Text.literal(prefix).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(startRgb))))
        } else {
            for (i in prefix.indices) {
                val rgb = ColorHelper.lerp(i.toFloat() / (prefix.length - 1), startRgb, endRgb)
                finalMessage.append(
                    Text.literal(prefix[i].toString()).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
                )
            }
        }
        finalMessage.append(Text.literal(" ").formatted(Formatting.RESET))

        for (part in messages) {
            val partText: Text = when (part) {
                is Text -> part
                is String -> TextComponent(part)
                else -> Text.literal(part.toString())
            }
            finalMessage.append(partText)
        }

        mc.inGameHud.chatHud.addMessage(finalMessage)
    }
}
