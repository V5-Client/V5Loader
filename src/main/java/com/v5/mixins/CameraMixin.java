package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Inject(method = "update", at = @At("TAIL"))
    private void v5$applyCameraOverride(DeltaTracker deltaTracker, CallbackInfo ci) {
        Object override = V5MixinStorage.get("cameraOverridePos", null);
        if (override instanceof Vec3 pos) {
            this.setPosition(pos);
        }
    }
}
