package com.chattriggers.ctjs.internal.listeners

import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.entity.BlockEntity
import com.chattriggers.ctjs.api.entity.Entity
import com.chattriggers.ctjs.api.entity.PlayerInteraction
import com.chattriggers.ctjs.api.inventory.Item
import com.chattriggers.ctjs.api.message.TextComponent
import com.chattriggers.ctjs.api.render.DrawContextHolder
import com.chattriggers.ctjs.api.render.Render2D
import com.chattriggers.ctjs.api.triggers.CancellableEvent
import com.chattriggers.ctjs.api.triggers.ChatTrigger
import com.chattriggers.ctjs.api.triggers.TriggerType
import com.chattriggers.ctjs.api.world.Scoreboard
import com.chattriggers.ctjs.api.world.TabList
import com.chattriggers.ctjs.api.world.World
import com.chattriggers.ctjs.api.world.block.BlockFace
import com.chattriggers.ctjs.api.world.block.BlockPos
import com.chattriggers.ctjs.internal.engine.CTEvents
import com.chattriggers.ctjs.internal.engine.JSContextFactory
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.utils.Initializer
import gg.essential.universal.UMatrixStack
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player as MCPlayer
import org.lwjgl.glfw.GLFW
import org.mozilla.javascript.Context

object ClientListener : Initializer {
    private const val HISTORY_LIMIT = 1000

    private var ticksPassed: Int = 0
    private val chatHistoryBuffer = ArrayDeque<TextComponent>(HISTORY_LIMIT)
    private val actionBarHistoryBuffer = ArrayDeque<TextComponent>(HISTORY_LIMIT)
    val chatHistory: List<TextComponent>
        get() = chatHistoryBuffer.toList()
    val actionBarHistory: List<TextComponent>
        get() = actionBarHistoryBuffer.toList()
    private val tasks = mutableListOf<Task>()
    private lateinit var packetContext: Context

    class Task(var delay: Int, val callback: () -> Unit)

    override fun init() {
        packetContext = JSContextFactory.enterContext()
        Context.exit()

        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ ->
            handleChatMessage(message, actionBar = false)
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            handleChatMessage(message, actionBar = overlay)
        }

        ClientTickEvents.START_CLIENT_TICK.register {
            synchronized(tasks) {
                tasks.removeAll { task ->
                    if (task.delay-- <= 0) {
                        Client.getMinecraft().submit(task.callback)
                        true
                    } else {
                        false
                    }
                }
            }

            if (World.isLoaded() && World.toMC()?.tickRateManager()?.runsNormally() == true) {
                TriggerType.TICK.triggerAll(ticksPassed)
                ticksPassed++

                Scoreboard.resetCache()
                TabList.resetCache()
            }
        }

        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            triggerMessageSent(message)
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { message ->
            triggerMessageSent("/$message")
        }

        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            // TODO: Why does Render2D.drawString not work in here?
            ScreenEvents.beforeExtract(screen).register { _, stack, mouseX, mouseY, partialTicks ->
                if (!JSLoader.hasTriggers(TriggerType.GUI_RENDER)) return@register
                DrawContextHolder.withContext(stack) {
                    Render2D.withMatrix(UMatrixStack(stack.pose()).toMC(), partialTicks) {
                        TriggerType.GUI_RENDER.triggerAll(mouseX, mouseY, screen)
                    }
                }
            }

            // TODO: Why does Render2D.drawString not work in here?
            ScreenEvents.afterExtract(screen).register { _, stack, mouseX, mouseY, partialTicks ->
                if (!JSLoader.hasTriggers(TriggerType.POST_GUI_RENDER)) return@register
                DrawContextHolder.withContext(stack) {
                    Render2D.withMatrix(UMatrixStack(stack.pose()).toMC(), partialTicks) {
                        TriggerType.POST_GUI_RENDER.triggerAll(mouseX, mouseY, screen, partialTicks)
                    }
                }
            }

            ScreenKeyboardEvents.allowKeyPress(screen).register { _, input ->
                if (!JSLoader.hasTriggers(TriggerType.GUI_KEY)) return@register true
                val event = CancellableEvent()
                TriggerType.GUI_KEY.triggerAll(GLFW.glfwGetKeyName(input.key, input.scancode), input.key, screen, event)
                !event.isCancelled()
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.remove(screen).register {
                if (!JSLoader.hasTriggers(TriggerType.GUI_CLOSED)) return@register
                TriggerType.GUI_CLOSED.triggerAll(screen)
            }
        }

        CTEvents.PACKET_RECEIVED.register { packet, ctx ->
            if (!JSLoader.hasTriggers(TriggerType.PACKET_RECEIVED)) return@register
            JSLoader.wrapInContext(packetContext) {
                TriggerType.PACKET_RECEIVED.triggerAll(packet, ctx)
            }
        }

        CTEvents.RENDER_TICK.register {
            if (!JSLoader.hasTriggers(TriggerType.STEP)) return@register
            TriggerType.STEP.triggerAll()
        }

        CTEvents.RENDER_OVERLAY.register { ctx, stack, partialTicks ->
            if (!JSLoader.hasTriggers(TriggerType.RENDER_OVERLAY)) return@register
            DrawContextHolder.withContext(ctx) {
                Render2D.withMatrix(stack, partialTicks) {
                    TriggerType.RENDER_OVERLAY.triggerAll(ctx)
                }
            }
        }

        CTEvents.RENDER_ENTITY.register { stack, entity, partialTicks, ci ->
            if (!JSLoader.hasTriggers(TriggerType.RENDER_ENTITY)) return@register
            Render2D.withMatrix(stack, partialTicks) {
                TriggerType.RENDER_ENTITY.triggerAll(Entity.fromMC(entity), partialTicks, ci)
            }
        }

        CTEvents.RENDER_BLOCK_ENTITY.register { stack, blockEntity, partialTicks, ci ->
            if (!JSLoader.hasTriggers(TriggerType.RENDER_BLOCK_ENTITY)) return@register
            Render2D.withMatrix(stack, partialTicks) {
                TriggerType.RENDER_BLOCK_ENTITY.triggerAll(BlockEntity(blockEntity), partialTicks, ci)
            }
        }

        AttackBlockCallback.EVENT.register { player, _, _, pos, direction ->
            if (!shouldTriggerPlayerInteract(player)) return@register InteractionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.AttackBlock,
                World.getBlockAt(BlockPos(pos)).withFace(BlockFace.fromMC(direction)),
                event,
            )

            event.toInteractionResult()
        }

        AttackEntityCallback.EVENT.register { player, _, _, entity, _ ->
            if (!shouldTriggerPlayerInteract(player)) return@register InteractionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.AttackEntity,
                Entity.fromMC(entity),
                event,
            )

            event.toInteractionResult()
        }

        CTEvents.BREAK_BLOCK.register { pos ->
            if (!JSLoader.hasTriggers(TriggerType.PLAYER_INTERACT)) return@register
            val event = CancellableEvent()
            TriggerType.PLAYER_INTERACT.triggerAll(PlayerInteraction.BreakBlock, World.getBlockAt(BlockPos(pos)), event)

            check(!event.isCancelled()) {
                "PlayerInteraction event of type BreakBlock is not cancellable"
            }
        }

        UseBlockCallback.EVENT.register { player, _, hand, hitResult ->
            if (!shouldTriggerPlayerInteract(player)) return@register InteractionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.UseBlock(hand),
                World.getBlockAt(BlockPos(hitResult.blockPos)).withFace(BlockFace.fromMC(hitResult.direction)),
                event,
            )

            event.toInteractionResult()
        }

        UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
            if (!shouldTriggerPlayerInteract(player)) return@register InteractionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.UseEntity(hand),
                Entity.fromMC(entity),
                event,
            )

            event.toInteractionResult()
        }

        UseItemCallback.EVENT.register { player, _, hand ->
            if (!shouldTriggerPlayerInteract(player)) return@register InteractionResult.PASS
            val event = CancellableEvent()

            val stack = player.getItemInHand(hand)

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.UseItem(hand),
                Item.fromMC(stack),
                event,
            )

            event.toInteractionResult()
        }
    }

    fun addTask(delay: Int, callback: () -> Unit) {
        synchronized(tasks) {
            tasks.add(Task(delay, callback))
        }
    }

    private fun handleChatMessage(message: Component, actionBar: Boolean): Boolean {
        val textComponent = TextComponent(message)
        val triggerType = if (actionBar) TriggerType.ACTION_BAR else TriggerType.CHAT
        val buffer = if (actionBar) actionBarHistoryBuffer else chatHistoryBuffer

        pushHistory(buffer, textComponent)

        if (!JSLoader.hasTriggers(triggerType)) return true
        val event = ChatTrigger.Event(textComponent)
        triggerType.triggerAll(event)
        return !event.isCancelled()
    }

    private fun triggerMessageSent(message: String): Boolean {
        if (!JSLoader.hasTriggers(TriggerType.MESSAGE_SENT)) return true

        val event = CancellableEvent()
        TriggerType.MESSAGE_SENT.triggerAll(message, event)
        return !event.isCancelled()
    }

    private fun pushHistory(buffer: ArrayDeque<TextComponent>, message: TextComponent) {
        if (buffer.size >= HISTORY_LIMIT) {
            buffer.removeFirst()
        }
        buffer.addLast(message)
    }

    private fun shouldTriggerPlayerInteract(player: MCPlayer) =
        player.level().isClientSide && JSLoader.hasTriggers(TriggerType.PLAYER_INTERACT)

    private fun CancellableEvent.toInteractionResult(): InteractionResult =
        if (isCancelled()) InteractionResult.FAIL else InteractionResult.PASS
}
