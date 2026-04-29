package com.v5.mixins;

import com.v5.qol.Xray;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import net.minecraft.fluid.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockRenderLayers.class)
public abstract class BlockRenderLayersMixin {
    @Inject(method = "getBlockLayer", at = @At("HEAD"), cancellable = true)
    private static void onGetBlockLayer(BlockState state, CallbackInfoReturnable<BlockRenderLayer> cir) {
        if (Xray.isEnabled) {
            int alpha = Xray.returnAlpha(null, state);
            if (state.isOf(Blocks.GOLD_BLOCK) || state.isOf(Blocks.BEDROCK))
                return;

            if (alpha > 0 && alpha < 255) {
                cir.setReturnValue(BlockRenderLayer.TRANSLUCENT);
            }
        }
    }

    @Inject(method = "getFluidLayer", at = @At("HEAD"), cancellable = true)
    private static void onGetFluidLayer(FluidState state, CallbackInfoReturnable<BlockRenderLayer> cir) {
        int alpha = Xray.returnAlpha(null, state.getBlockState());
        if (Xray.isEnabled) {
            if (alpha > 0 && alpha < 255) {
                cir.setReturnValue(BlockRenderLayer.TRANSLUCENT);
            }
        }
    }
}

