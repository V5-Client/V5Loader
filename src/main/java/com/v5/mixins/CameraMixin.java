package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
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

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void v5$applyCameraOverride(DeltaTracker deltaTracker, CallbackInfo ci) {
        Object yaw = V5MixinStorage.get("cameraOverrideYaw", null);
        Object pitch = V5MixinStorage.get("cameraOverridePitch", null);
        if (V5MixinStorage.getBoolean("freelookEnabled", false)
                && yaw instanceof Number yawNumber
                && pitch instanceof Number pitchNumber) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                float yawRadians = yawNumber.floatValue() * Mth.DEG_TO_RAD;
                float pitchRadians = pitchNumber.floatValue() * Mth.DEG_TO_RAD;
                float cosPitch = Mth.cos(pitchRadians);
                Object storedDistance = V5MixinStorage.get("freelookCameraDistance", 4.0);
                double distance = storedDistance instanceof Number number ? Mth.clamp(number.doubleValue(), 1.0, 12.0) : 4.0;
                Vec3 eyePos = player.getEyePosition(deltaTracker.getGameTimeDeltaPartialTick(false));
                this.setPosition(eyePos.add(
                        Mth.sin(yawRadians) * cosPitch * distance,
                        Mth.sin(pitchRadians) * distance,
                        -Mth.cos(yawRadians) * cosPitch * distance));
            }
        } else {
            Object override = V5MixinStorage.get("cameraOverridePos", null);
            if (override instanceof Vec3 pos) {
                this.setPosition(pos);
            }
        }

        if (yaw instanceof Number yawNumber && pitch instanceof Number pitchNumber) {
            this.setRotation(yawNumber.floatValue(), pitchNumber.floatValue());
        }
    }
}
