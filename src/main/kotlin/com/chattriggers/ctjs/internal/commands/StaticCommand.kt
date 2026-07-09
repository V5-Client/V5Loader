package com.chattriggers.ctjs.internal.commands

import com.chattriggers.ctjs.api.triggers.CommandTrigger
import com.chattriggers.ctjs.internal.mixins.commands.CommandNodeAccessor
import com.chattriggers.ctjs.internal.utils.asMixin
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.SharedSuggestionProvider

internal class StaticCommand(
    val trigger: CommandTrigger,
    override val name: String,
    private val aliases: Set<String>,
    override val overrideExisting: Boolean,
    private val staticSuggestions: List<String>,
    private val dynamicSuggestions: ((List<String>) -> List<String>)?,
) : Command {
    override fun registerImpl(dispatcher: CommandDispatcher<SharedSuggestionProvider>) {
        val builder = literal(name)
            .then(argument("args", StringArgumentType.greedyString())
                .suggests { ctx, builder ->
                    val suggestions = dynamicSuggestions?.let { suggest ->
                        val args = try {
                            StringArgumentType.getString(ctx, "args").split(" ")
                        } catch (e: IllegalArgumentException) {
                            emptyList()
                        }

                        suggest(args)
                    } ?: staticSuggestions

                    for (suggestion in suggestions)
                        builder.suggest(suggestion)

                    builder.buildFuture()
                }
                .onExecute {
                    trigger.trigger(StringArgumentType.getString(it, "args").split(" ").toTypedArray())
                })
            .onExecute { trigger.trigger(emptyArray()) }

        val node = dispatcher.register(builder)

        // Can't use .redirect() since it doesn't work without arguments
        for (alias in aliases) {
            val aliasNode = literal(alias)
            node.children.forEach(aliasNode::then)
            aliasNode.executes(node.command)

            dispatcher.register(aliasNode)
        }
    }

    override fun unregisterImpl(dispatcher: CommandDispatcher<SharedSuggestionProvider>) {
        super.unregisterImpl(dispatcher)
        dispatcher.root.asMixin<CommandNodeAccessor>().apply {
            aliases.forEach { alias ->
                childNodes.remove(alias)
                literals.remove(alias)
            }
        }
    }

    companion object : CommandCollection()
}
