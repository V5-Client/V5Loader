package com.v5.swift.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.v5.swift.Swift
import com.v5.swift.cache.CachedWorld
import com.v5.swift.event.Context
import com.v5.swift.finder.calculate.Path
import com.v5.swift.finder.calculate.path.AStarPathfinder
import com.v5.swift.finder.goal.Goal
import com.v5.swift.finder.goal.GoalFly
import com.v5.swift.finder.movement.CalculationContext
import com.v5.swift.util.PlayerUtils
import com.v5.render.RenderUtils
import java.awt.Color
import java.util.concurrent.Future
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d

object PathCommand {

  @Volatile
  private var path: Path? = null
  @Volatile
  private var isSearching = false
  private var currentTask: Future<*>? = null
  private var showRawNodes = false

  fun dispatch() {
    ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
      dispatcher.register(
        ClientCommandManager.literal("swiftpf")
          .then(
            ClientCommandManager.literal("clear").executes {
              path = null
              isSearching = false
              showRawNodes = false

              currentTask?.cancel(true)
              currentTask = null

              MinecraftClient.getInstance().inGameHud.chatHud.addMessage(Text.of("Cleared"))
              1
            }
          )
          .then(
            ClientCommandManager.literal("stats").executes {
              val stats = CachedWorld.getCacheStats()
              MinecraftClient.getInstance().inGameHud.chatHud.addMessage(Text.of("§7Cache: $stats"))
              1
            }
          )
          .then(
            ClientCommandManager.argument("x", IntegerArgumentType.integer())
              .then(
                ClientCommandManager.argument("y", IntegerArgumentType.integer())
                  .then(
                    ClientCommandManager.argument("z", IntegerArgumentType.integer())
                      .executes { context -> executePathfind(context, false, false) }
                      .then(
                        ClientCommandManager.argument("isFly", BoolArgumentType.bool())
                          .executes { context ->
                            executePathfind(context, BoolArgumentType.getBool(context, "isFly"), false)
                          }
                          .then(
                            ClientCommandManager.argument("showNodes", BoolArgumentType.bool())
                              .executes { context ->
                                executePathfind(
                                  context,
                                  BoolArgumentType.getBool(context, "isFly"),
                                  BoolArgumentType.getBool(context, "showNodes")
                                )
                              }
                          )
                      )
                  )
              )
          )
      )
    }
  }

  private fun executePathfind(
    context: CommandContext<FabricClientCommandSource>,
    isFly: Boolean,
    showNodes: Boolean
  ): Int {
    if (isSearching) {
      context.source.sendFeedback(Text.of("§eAlready searching, please wait..."))
      return 0
    }

    val x = IntegerArgumentType.getInteger(context, "x")
    val y = IntegerArgumentType.getInteger(context, "y")
    val z = IntegerArgumentType.getInteger(context, "z")

    showRawNodes = showNodes

    val mc = MinecraftClient.getInstance()
    val standingOn = PlayerUtils.getBlockStandingOn()

    if (standingOn == null) {
      mc.inGameHud.chatHud.addMessage(Text.of("§cCouldn't get player position"))
      return 0
    }

    isSearching = true
    mc.inGameHud.chatHud.addMessage(Text.of("§7Searching for path..."))

    currentTask = Swift.Companion.executor.submit {
      try {
        val ctx = CalculationContext()
        val goal = if (isFly)
            GoalFly(standingOn.x, standingOn.y, standingOn.z, x, y, z, ctx)
        else
            Goal(standingOn.x, standingOn.z, x, y, z, ctx)

        val result = AStarPathfinder(
            startPoints = arrayOf(intArrayOf(standingOn.x, standingOn.y, standingOn.z)),
            goal = goal,
            ctx = ctx,
            isFly = isFly
        ).findPath()

        mc.execute {
          val chat = mc.inGameHud.chatHud

          if (result != null) {
            chat.addMessage(
              Text.of(
                "§aPath found in ${result.timeTaken}ms (${result.points.size} nodes, ${result.keyNodes.size} keynodes, ${result.nodesExplored} explored, ${
                  String.format(
                    "%.1f",
                    result.nanosPerNode)
                }ns/node)"
              )
            )
            path = result
          } else {
            chat.addMessage(Text.of("§cNo path found"))
          }

          isSearching = false
        }
      } catch (e: Exception) {
        mc.execute {
          mc.inGameHud.chatHud.addMessage(Text.of("§cPathfinding error: ${e.message}"))
          isSearching = false
        }
      }
    }

    return 1
  }

  fun onRender(ctx: Context) {
    val currentPath = path ?: return
    var prev: Vec3d? = null

    if (showRawNodes) {
      val green = Color(0, 255, 0, 100)
      for (pos in currentPath.points) {
        val box = Box(
          pos.x + 0.35, pos.y + 0.35, pos.z + 0.35,
          pos.x + 0.65, pos.y + 0.65, pos.z + 0.65
        )
        RenderUtils.drawBox(box, green.toRenderColor())
      }
    }

    for (pos in currentPath.keyNodes) {
      val center = Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)

      prev?.let { vec ->
        RenderUtils.drawLine(vec, center, Color(255, 132, 94).toRenderColor(), thickness = 2F)
      }

      val box = Box(
        pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
        pos.x + 1.0, pos.y - 1.0, pos.z + 1.0
      )

      RenderUtils.drawBox(box, Color(255, 132, 94).toRenderColor())
      prev = center
    }
  }

  private fun Color.toRenderColor() = RenderUtils.Color(red, green, blue, alpha)

}
