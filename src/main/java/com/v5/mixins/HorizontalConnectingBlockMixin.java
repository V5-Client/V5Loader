package com.v5.mixins;

import net.minecraft.block.HorizontalConnectingBlock;
import net.minecraft.block.StainedGlassPaneBlock;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HorizontalConnectingBlock.class)
public class HorizontalConnectingBlockMixin {
    @Inject(method = "getOutlineShape", at = @At("HEAD"), cancellable = true)
    private void v5$getOutlineShape(CallbackInfoReturnable<VoxelShape> cir) {
        if ((Object) this instanceof StainedGlassPaneBlock) {
            cir.setReturnValue(VoxelShapes.fullCube());
        }
    }
}
