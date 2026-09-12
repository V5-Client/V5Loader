package com.chattriggers.ctjs.api.commands

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.ChatFormatting
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture

/** Version-neutral replacement for the removed vanilla ColorArgument in 26.2. */
internal object ColorArgumentCompat : ArgumentType<ChatFormatting> {
    private val colors = ChatFormatting.entries.filter { it <= ChatFormatting.WHITE || it == ChatFormatting.RESET }
    private val invalidColor = DynamicCommandExceptionType {
        Component.translatableEscape("argument.color.invalid", it)
    }

    override fun parse(reader: StringReader): ChatFormatting {
        val value = reader.readUnquotedString()
        return colors.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw invalidColor.createWithContext(reader, value)
    }

    override fun <S> listSuggestions(
        context: CommandContext<S>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(colors.map { it.name.lowercase() }, builder)

    override fun getExamples(): Collection<String> = listOf("red", "green")
}
