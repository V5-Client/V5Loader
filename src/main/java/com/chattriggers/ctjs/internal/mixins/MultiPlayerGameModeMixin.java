package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.client.Player;
import com.chattriggers.ctjs.api.inventory.Item;
import com.chattriggers.ctjs.api.triggers.TriggerType;
import com.chattriggers.ctjs.internal.engine.CTEvents;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    //? if >=26.3 {
    /*
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void v5$dropItem(LocalPlayer player, boolean entireStack, CallbackInfo ci) {
        Item stack = Player.getHeldItem();
        if (stack != null && !stack.getMcValue().isEmpty()) {
            TriggerType.DROP_ITEM.triggerAll(stack, entireStack, ci);
        }
    }
    *///?}

    @Inject(
        method = "destroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;destroy(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"
        )
    )
    private void injectBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        CTEvents.BREAK_BLOCK.invoker().breakBlock(pos);
    }
}
