package com.chattriggers.ctjs.api.inventory

import com.chattriggers.ctjs.api.CTWrapper
import com.chattriggers.ctjs.api.client.Player
import com.chattriggers.ctjs.api.message.TextComponent
import com.chattriggers.ctjs.api.render.DrawContextHolder
import com.chattriggers.ctjs.api.world.World
import com.chattriggers.ctjs.api.world.block.Block
import com.chattriggers.ctjs.api.world.block.BlockPos
import com.chattriggers.ctjs.internal.Skippable
import com.chattriggers.ctjs.internal.TooltipOverridable
import com.chattriggers.ctjs.internal.utils.asMixin
import net.minecraft.world.level.block.state.pattern.BlockInWorld
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import kotlin.jvm.optionals.getOrNull

class Item(override val mcValue: ItemStack) : CTWrapper<ItemStack> {
    val type: ItemType = ItemType(mcValue.item)

    init {
        require(!mcValue.isEmpty) {
            "Can not wrap empty ItemStack as an Item"
        }
    }

    constructor(type: ItemType) : this(type.toMC().defaultInstance)

    fun getStackSize(): Int = mcValue.count

    fun setStackSize(size: Int) = apply {
        mcValue.count = size
    }

    fun getEnchantments() = EnchantmentHelper.getEnchantmentsForCrafting(mcValue).keySet().associate {
        it.unwrapKey().getOrNull() to EnchantmentHelper.getItemEnchantmentLevel(it, mcValue)
    }

    fun isEnchantable() = mcValue.isEnchantable

    fun isEnchanted() = mcValue.isEnchanted

    fun canPlaceOn(pos: BlockPos) =
        mcValue.canPlaceOnBlockInAdventureMode(BlockInWorld(requireNotNull(World.toMC()), pos.toMC(), false))

    fun canPlaceOn(block: Block) = canPlaceOn(block.pos)

    fun canHarvest(pos: BlockPos) =
        mcValue.canBreakBlockInAdventureMode(BlockInWorld(requireNotNull(World.toMC()), pos.toMC(), false))

    fun canHarvest(block: Block) = canHarvest(block.pos)

    fun getDurability() = getMaxDamage() - getDamage()

    fun getMaxDamage() = mcValue.maxDamage

    fun getDamage() = mcValue.damageValue

    fun isDamageable() = mcValue.isDamageableItem

    fun getName(): String = TextComponent(mcValue.hoverName).formattedText

    fun setName(name: TextComponent?) = apply {
        mcValue.set(DataComponents.CUSTOM_NAME, name)
    }

    fun resetName() {
        setName(null)
    }

    @JvmOverloads
    fun getLore(advanced: Boolean = false): List<TextComponent> {
        mcValue.asMixin<Skippable>().ctjs_setShouldSkip(true)
        try {
            return mcValue.getTooltipLines(
                TooltipContext.EMPTY,
                Player.toMC(),
                if (advanced) TooltipFlag.ADVANCED else TooltipFlag.NORMAL,
            ).map { TextComponent(it) }
        } finally {
            mcValue.asMixin<Skippable>().ctjs_setShouldSkip(false)
        }
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

        context.pose().pushMatrix()
        context.pose().translate(x, y)
        context.pose().scale(scale, scale)

        try {
            context.item(mcValue, 0, 0)
        } catch (e: Exception) {
            println("Draw Error: ${e.message}")
        } finally {
            context.pose().popMatrix()
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
