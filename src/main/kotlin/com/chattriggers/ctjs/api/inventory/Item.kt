package com.chattriggers.ctjs.api.inventory

import com.chattriggers.ctjs.api.CTWrapper
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.client.Player
import com.chattriggers.ctjs.api.entity.Entity
import com.chattriggers.ctjs.api.message.TextComponent
import com.chattriggers.ctjs.api.render.Renderer
import com.chattriggers.ctjs.api.world.World
import com.chattriggers.ctjs.api.world.block.Block
import com.chattriggers.ctjs.api.world.block.BlockPos
import com.chattriggers.ctjs.internal.Skippable
import com.chattriggers.ctjs.internal.TooltipOverridable
import com.chattriggers.ctjs.internal.utils.asMixin
import com.v5.render.helper.DrawContextHolder
import net.minecraft.block.pattern.CachedBlockPosition
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.item.ItemRenderState
import net.minecraft.component.DataComponentTypes
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.item.Item.TooltipContext
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemDisplayContext
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.util.crash.CrashException
import net.minecraft.util.crash.CrashReport
import kotlin.jvm.optionals.getOrNull
import net.minecraft.client.gui.DrawContext

class Item(override val mcValue: ItemStack) : CTWrapper<ItemStack> {
    val type: ItemType = ItemType(mcValue.item)

    init {
        require(!mcValue.isEmpty) {
            "Can not wrap empty ItemStack as an Item"
        }
    }

    constructor(type: ItemType) : this(type.toMC().defaultStack)

    fun getHolder(): Entity? = mcValue.holder?.let(Entity::fromMC)

    fun getStackSize(): Int = mcValue.count

    fun setStackSize(size: Int) = apply {
        mcValue.count = size
    }

    fun getEnchantments() = EnchantmentHelper.getEnchantments(mcValue).enchantments.associate {
        it.key.getOrNull() to EnchantmentHelper.getLevel(it, mcValue)
    }

    fun isEnchantable() = mcValue.isEnchantable

    fun isEnchanted() = mcValue.hasEnchantments()

    fun canPlaceOn(pos: BlockPos) =
        mcValue.canPlaceOn(CachedBlockPosition(World.toMC(), pos.toMC(), false))

    fun canPlaceOn(block: Block) = canPlaceOn(block.pos)

    fun canHarvest(pos: BlockPos) =
        mcValue.canBreak(CachedBlockPosition(World.toMC(), pos.toMC(), false))

    fun canHarvest(block: Block) = canHarvest(block.pos)

    fun getDurability() = getMaxDamage() - getDamage()

    fun getMaxDamage() = mcValue.maxDamage

    fun getDamage() = mcValue.damage

    fun isDamageable() = mcValue.isDamageable

    fun getName(): String = TextComponent(mcValue.name).formattedText

    fun setName(name: TextComponent?) = apply {
        mcValue.set(DataComponentTypes.CUSTOM_NAME, name)
    }

    fun resetName() {
        setName(null)
    }

    @JvmOverloads
    fun getLore(advanced: Boolean = false): List<TextComponent> {
        mcValue.asMixin<Skippable>().ctjs_setShouldSkip(true)
        val tooltip = mcValue.getTooltip(
            TooltipContext.DEFAULT,
            Player.toMC(),
            if (advanced) TooltipType.ADVANCED else TooltipType.BASIC,
        ).mapTo(mutableListOf()) { TextComponent(it) }

        mcValue.asMixin<Skippable>().ctjs_setShouldSkip(false)

        return tooltip
    }

    fun setLore(lore: List<TextComponent>) {
        mcValue.asMixin<TooltipOverridable>().apply {
            ctjs_setTooltip(lore)
            ctjs_setShouldOverrideTooltip(true)
        }
    }

    fun resetLore() {
        mcValue.asMixin<TooltipOverridable>().ctjs_setShouldOverrideTooltip(false)
    }

    // TODO: make a component wrapper?
    fun getNBT() = mcValue.components

    @JvmOverloads
    fun draw(x: Float = 0f, y: Float = 0f, scale: Float = 1f) {
        if (mcValue.isEmpty) return
        val context = DrawContextHolder.currentContext ?: return

        context.matrices.pushMatrix()
        context.matrices.translate(x, y)
        context.matrices.scale(scale, scale)

        // DEBUG: Ensure we are drawing at a Z-level that isn't hidden
        // Standard DrawContext.drawItem handles its own Z, but we can wrap it
        try {
            // In 1.21.10, drawItem is the safest method.
            // Ensure mcValue is a valid net.minecraft.item.ItemStack
            context.drawItem(mcValue, 0, 0)
        } catch (e: Exception) {
            // If this hits, the context is likely invalid/disposed
            println("Draw Error: ${e.message}")
        } finally {
            context.matrices.popMatrix()
        }
    }

    override fun toString(): String = "Item{name=${getName()}, type=${type.getRegistryName()}, size=${getStackSize()}}"

    companion object {
        @JvmStatic
        fun fromMC(mcValue: ItemStack): Item? {
            return if (mcValue.isEmpty) {
                null
            } else {
                Item(mcValue)
            }
        }
    }
}
