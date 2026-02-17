package dev.quiteboring.swift.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import dev.quiteboring.swift.event.Context
import dev.quiteboring.swift.finder.movement.CalculationContext
import dev.quiteboring.swift.util.render.drawBox
import java.awt.Color
import kotlin.math.abs
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box

object HeatmapCommand {

  private var boxes: List<Pair<Box, Color>>? = null
  private var mode = Mode.PENALTY
  private var cachedCenter: BlockPos? = null
  private var cachedRadius = 0

  private const val ALPHA = 100

  enum class Mode { PENALTY, EDGE, WALL }

  fun dispatch() {
    ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
      dispatcher.register(
        ClientCommandManager.literal("heatmap")
          .then(
            ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, 64))
              .executes { ctx ->
                val radius = IntegerArgumentType.getInteger(ctx, "radius")
                val mc = MinecraftClient.getInstance()
                val player = mc.player ?: return@executes 1

                val pos = BlockPos.ofFloored(player.x, player.y, player.z)
                cachedCenter = pos
                cachedRadius = radius
                generate()
                1
              }
          )
          .then(ClientCommandManager.literal("clear").executes {
            boxes = null
            cachedCenter = null
            cachedRadius = 0
            1
          })
          .then(ClientCommandManager.literal("penalty").executes { setMode(Mode.PENALTY); 1 })
          .then(ClientCommandManager.literal("edge").executes { setMode(Mode.EDGE); 1 })
          .then(ClientCommandManager.literal("wall").executes { setMode(Mode.WALL); 1 })
          .then(ClientCommandManager.literal("inspect").executes { inspect(); 1 })
      )
    }
  }

  private fun setMode(newMode: Mode) {
    mode = newMode
    println("Mode: $mode")

    if (cachedCenter != null) {
      generate()
    }
  }

  private fun inspect() {
    val mc = MinecraftClient.getInstance()
    val ctx = CalculationContext()

    val hitResult = mc.crosshairTarget
    val pos = if (hitResult is BlockHitResult) {
      val blockPos = hitResult.blockPos
      val side = hitResult.side

      if (side == net.minecraft.util.math.Direction.UP) {
        BlockPos(blockPos.x, blockPos.y + 1, blockPos.z)
      } else {
        blockPos.offset(side)
      }
    } else {
      val player = mc.player ?: return
      BlockPos.ofFloored(player.x, player.y, player.z)
    }

    val x = pos.x
    val y = pos.y
    val z = pos.z

    println("Inspecting: ($x, $y, $z)")
    println("Edge: ${ctx.wdc.getEdgeDistance(x, y, z)}")
    println("Wall: ${ctx.wdc.getWallDistance(x, y, z)}")
    println("Penalty: ${ctx.wdc.getPathPenalty(x, y, z)}")
    println("Safe: ${ctx.safeCache.isSafe(x, y, z)}")
  }

  private data class BlockData(val box: Box, val value: Double)

  private fun generate() {
    val center = cachedCenter ?: return
    val radius = cachedRadius
    val ctx = CalculationContext()
    val world = MinecraftClient.getInstance().world ?: return
    val blockData = mutableListOf<BlockData>()

    val radiusSq = radius * radius
    val searchOrder = (-10..10).sortedBy { abs(it) }

    for (dx in -radius..radius) {
      for (dz in -radius..radius) {
        if (dx * dx + dz * dz > radiusSq) continue

        val x = center.x + dx
        val z = center.z + dz

        for (dy in searchOrder) {
          val y = center.y + dy

          if (ctx.safeCache.isSafe(x, y, z)) {
            val blockPos = BlockPos(x, y - 1, z)
            val shape = world.getBlockState(blockPos).getOutlineShape(world, blockPos)

            val box = if (!shape.isEmpty) {
              shape.boundingBox.offset(x.toDouble(), (y - 1).toDouble(), z.toDouble())
            } else {
              Box(x.toDouble(), (y - 1).toDouble(), z.toDouble(), x + 1.0, y.toDouble(), z + 1.0)
            }

            val value = when (mode) {
              Mode.PENALTY -> ctx.wdc.getPathPenalty(x, y, z)
              Mode.EDGE -> ctx.wdc.getEdgeDistance(x, y, z).toDouble()
              Mode.WALL -> ctx.wdc.getWallDistance(x, y, z).toDouble()
            }

            blockData.add(BlockData(box, value))
            break
          }
        }
      }
    }

    if (blockData.isEmpty()) {
      boxes = emptyList()
      println("Heatmap: 0 points")
      return
    }

    val minValue = blockData.minOf { it.value }
    val maxValue = blockData.maxOf { it.value }

    val result = blockData.map { data ->
      val color = gradientColor(data.value, minValue, maxValue)
      data.box to color
    }

    boxes = result
    println("Heatmap: ${result.size} points (min: $minValue, max: $maxValue)")
  }

  private fun gradientColor(value: Double, min: Double, max: Double): Color {
    val t = if (max == min) {
      0.0
    } else {
      (value - min) / (max - min)
    }

    val red = (255 * t).toInt().coerceIn(0, 255)
    val green = (255 * (1 - t)).toInt().coerceIn(0, 255)

    return Color(red, green, 0, ALPHA)
  }

  fun onRender(ctx: Context) {
    boxes?.let {
      it.forEach { (box, color) ->
        ctx.drawBox(box, color)
      }
    }
  }

}
