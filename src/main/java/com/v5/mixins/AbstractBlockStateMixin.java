package com.v5.mixins;

import com.v5.qol.Xray;
import net.minecraft.block.AbstractBlock.AbstractBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractBlockState.class)
public class AbstractBlockStateMixin {

    @Inject(at = @At("TAIL"), method = {
        "getAmbientOcclusionLightLevel(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)F"}, cancellable = true)
    private void onGetAmbientOcclusionLightLevel(BlockView blockView, BlockPos blockPos, CallbackInfoReturnable<Float> cir) {
        if (Xray.isEnabled) {
            cir.setReturnValue(1F);
        }
    }
}
