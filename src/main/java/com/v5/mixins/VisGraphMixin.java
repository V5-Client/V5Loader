package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VisGraph.class)
public class VisGraphMixin {
    @Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true)
    private void v5$disableFreecamOcclusion(BlockPos pos, CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("freecamEnabled", false)) ci.cancel();
    }
}
