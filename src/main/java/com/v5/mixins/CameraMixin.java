package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void v5$applyCameraOverride(CallbackInfo ci) {
        Object override = V5MixinStorage.get("cameraOverridePos", null);
        if (override instanceof Vec3d pos) {
            this.setPos(pos);
        }
    }
}
