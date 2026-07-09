package com.chattriggers.ctjs.internal.commands

import com.chattriggers.ctjs.api.commands.DynamicCommands
import com.chattriggers.ctjs.api.commands.RootCommand
import com.chattriggers.ctjs.internal.CTClientCommandSource
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.mixins.commands.CommandContextAccessor
import com.chattriggers.ctjs.internal.utils.asMixin
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.commands.SharedSuggestionProvider
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject

object DynamicCommand {
    sealed class Node(val parent: Node?) {
        var method: Function? = null
        var hasRedirect = false
        val children = mutableListOf<Node>()
        var builder: ArgumentBuilder<SharedSuggestionProvider, *>? = null

        open class Literal(parent: Node?, val name: String) : Node(parent)

        class Root(name: String) : Literal(null, name), RootCommand {
            var commandNode: LiteralCommandNode<SharedSuggestionProvider>? = null

            override fun register() {
                DynamicCommands.register(CommandImpl(this))
            }
        }

        class Argument(parent: Node?, val name: String, val type: ArgumentType<*>) : Node(parent)

        class Redirect(parent: Node?, val target: Root) : Node(parent)

        class RedirectToCommandNode(parent: Node?, val target: CommandNode<SharedSuggestionProvider>) : Node(parent)

        fun initialize(dispatcher: CommandDispatcher<SharedSuggestionProvider>) {
            if (this is Redirect) {
                check(method == null)
                check(children.isEmpty())
                target.initialize(dispatcher)

                requireNotNull(parent?.builder).redirect(target.commandNode) {
                    it.copyArgumentsToSource()
                    it.source
                }

                return
            }

            if (this is RedirectToCommandNode) {
                check(method == null)
                check(children.isEmpty())

                requireNotNull(parent?.builder).redirect(target) {
                    it.copyArgumentsToSource()
                    it.source
                }

                return
            }

            val currentBuilder = when (this) {
                is Literal -> literal(name)
                is Argument -> argument(name, type)
            }
            builder = currentBuilder

            // The call to .then() below builds a node which check the command, so we
            // need to call .execute() and child..initialize() before then if necessary
            val execute = method
            if (execute != null) {
                currentBuilder.executes { ctx ->
                    val obj = NativeObject()
                    val commandSource = ctx.source.asMixin<CTClientCommandSource>()

                    for ((key, value) in commandSource.contextValues)
                        ScriptableObject.putProperty(obj, key, value)
                    for ((key, arg) in ctx.asMixin<CommandContextAccessor>().arguments)
                        ScriptableObject.putProperty(obj, key, arg.result)

                    commandSource.contextValues.clear()

                    JSLoader.invoke(execute, arrayOf(obj))
                    1
                }
            }

            for (child in children)
                child.initialize(dispatcher)

            parent?.builder?.then(currentBuilder)
        }
    }

    class CommandImpl(private val node: Node.Root) : Command {
        override val overrideExisting = true
        override val name = node.name

        override fun registerImpl(dispatcher: CommandDispatcher<SharedSuggestionProvider>) {
            node.initialize(dispatcher)
            val builder = requireNotNull(node.builder) as LiteralArgumentBuilder<SharedSuggestionProvider>
            node.commandNode = dispatcher.register(builder)
        }
    }

    private fun CommandContext<SharedSuggestionProvider>.copyArgumentsToSource() {
        for ((name, arg) in asMixin<CommandContextAccessor>().arguments)
            source.asMixin<CTClientCommandSource>().contextValues[name] = arg.result
    }
}
