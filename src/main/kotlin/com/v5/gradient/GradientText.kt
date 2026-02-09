package com.v5.gradient

import kotlin.math.roundToInt
import net.minecraft.client.MinecraftClient
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting

object Chat {

    private val mc = MinecraftClient.getInstance()
    private val legacyColors = mapOf(
        '0' to 0x000000, // Black
        '1' to 0x0000AA, // Dark Blue
        '2' to 0x00AA00, // Dark Green
        '3' to 0x00AAAA, // Dark Aqua
        '4' to 0xAA0000, // Dark Red
        '5' to 0xAA00AA, // Dark Purple
        '6' to 0xFFAA00, // Gold
        '7' to 0xAAAAAA, // Gray
        '8' to 0x555555, // Dark Gray
        '9' to 0x5555FF, // Blue
        'a' to 0x55FF55, // Green
        'b' to 0x55FFFF, // Aqua
        'c' to 0xFF5555, // Red
        'd' to 0xFF55FF, // Light Purple
        'e' to 0xFFFF55, // Yellow
        'f' to 0xFFFFFF  // White
    )

    @JvmStatic
    fun sendGradientMsg(prefix: String, startRgb: Int, endRgb: Int, vararg messages: Any) {
        val finalMessage = Text.empty()

        finalMessage.append(buildGradient(prefix, startRgb, endRgb))
        finalMessage.append(Text.literal(" ").formatted(Formatting.RESET))

        for (part in messages) {
            val partText: Text = when (part) {
                is Text -> part
                is String -> parseColoredText(part)
                else -> Text.literal(part.toString())
            }
            finalMessage.append(partText)
        }

        mc.inGameHud.chatHud.addMessage(finalMessage)
    }

    fun buildGradient(text: String, startRgb: Int, endRgb: Int): MutableText {
        val result = Text.empty()
        val length = text.length

        if (length <= 1) {
            return Text.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(startRgb)))
        }

        val sr = (startRgb shr 16) and 0xFF
        val sg = (startRgb shr 8) and 0xFF
        val sb = startRgb and 0xFF

        val er = (endRgb shr 16) and 0xFF
        val eg = (endRgb shr 8) and 0xFF
        val eb = endRgb and 0xFF

        for (i in text.indices) {
            val ratio = i.toDouble() / (length - 1)

            val r = (sr + ratio * (er - sr)).roundToInt()
            val g = (sg + ratio * (eg - sg)).roundToInt()
            val b = (sb + ratio * (eb - sb)).roundToInt()

            val rgb = (r shl 16) or (g shl 8) or b

            val charText = Text.literal(text[i].toString())
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
            result.append(charText)
        }

        return result
    }

    fun parseColoredText(input: String): MutableText {
        val result = Text.empty()
        var currentStyle = Style.EMPTY
        var i = 0

        while (i < input.length) {
            val ch = input[i]

            if ((ch == '&' || ch == '§') && i + 1 < input.length) {
                val next = input[i + 1].lowercaseChar()

                // Legacy color codes
                if (legacyColors.containsKey(next)) {
                    val color = legacyColors[next]!!
                    currentStyle = currentStyle.withColor(TextColor.fromRgb(color))
                    i += 2
                    continue
                }

                when (next) {
                    'l' -> currentStyle = currentStyle.withBold(true)
                    'o' -> currentStyle = currentStyle.withItalic(true)
                    'n' -> currentStyle = currentStyle.withUnderline(true)
                    'm' -> currentStyle = currentStyle.withStrikethrough(true)
                    'k' -> currentStyle = currentStyle.withObfuscated(true)
                    'r' -> currentStyle = Style.EMPTY
                }
                if (next in listOf('l','o','n','m','k','r')) {
                    i += 2
                    continue
                }

                if (next == '#' && i + 7 < input.length) {
                    val hexPart = input.substring(i + 2, i + 8)
                    if (hexPart.matches(Regex("[0-9a-fA-F]{6}"))) {
                        currentStyle = currentStyle.withColor(TextColor.fromRgb(hexPart.toInt(16)))
                        i += 8
                        continue
                    }
                }
            }

            // Normal character with current style
            val charText = Text.literal(ch.toString()).setStyle(currentStyle)
            result.append(charText)
            i++
        }

        return result
    }

}





