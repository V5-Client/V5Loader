package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Frustum.class)
public class FrustumMixin {
    @Inject(method = "cubeInFrustum(DDDDDD)I", at = @At("HEAD"), cancellable = true)
    private void v5$showEverythingInFreecam(double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ,
                                             CallbackInfoReturnable<Integer> cir) {
        if (V5MixinStorage.getBoolean("freecamEnabled", false)) cir.setReturnValue(FrustumIntersection.INSIDE);
    }
}
