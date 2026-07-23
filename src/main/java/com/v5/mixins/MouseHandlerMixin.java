package com.v5.mixins;

import com.chattriggers.ctjs.api.client.Client;
import com.mojang.blaze3d.platform.InputConstants;
import com.v5.storage.V5MixinStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    private int v5$guiMouseButton = -1;

    private boolean v5$cameraLookEnabled() {
        return V5MixinStorage.getBoolean("freecamEnabled", false)
                || V5MixinStorage.getBoolean("freelookEnabled", false);
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void v5$cancelFreecamClick(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (action == GLFW.GLFW_PRESS && client.screen != null) {
            v5$guiMouseButton = button.button();
        } else if (action == GLFW.GLFW_RELEASE) {
            if (button.button() == v5$guiMouseButton) {
                v5$guiMouseButton = -1;
            } else if (!v5$cameraLookEnabled() || (button.button() != 0 && button.button() != 1)) {
                Client.releaseHeldKey(InputConstants.Type.MOUSE.getOrCreate(button.button()));
            }
        }

        if (v5$cameraLookEnabled()
                && client.screen == null
                && (button.button() == 0 || button.button() == 1)) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "turnPlayer(D)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void v5$turnFreecam(LocalPlayer player, double yawDelta, double pitchDelta) {
        if (!v5$cameraLookEnabled()) {
            player.turn(yawDelta, pitchDelta);
            return;
        }

        Object yaw = V5MixinStorage.get("cameraOverrideYaw", player.getYRot());
        Object pitch = V5MixinStorage.get("cameraOverridePitch", player.getXRot());
        if (yaw instanceof Number yawNumber && pitch instanceof Number pitchNumber) {
            V5MixinStorage.set("cameraOverrideYaw", yawNumber.floatValue() + (float) yawDelta * 0.15F);
            V5MixinStorage.set(
                    "cameraOverridePitch",
                    Mth.clamp(pitchNumber.floatValue() + (float) pitchDelta * 0.15F, -90.0F, 90.0F));
        }
    }

    @Inject(method = "grabMouse()V", at = @At("HEAD"), cancellable = true)
    private void v5$lockCursor(CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            Client.resumeHeldKeys();
            ci.cancel();
        }
    }

    @Inject(method = "grabMouse()V", at = @At("TAIL"))
    private void v5$restoreHeldKeys(CallbackInfo ci) {
        Client.resumeHeldKeys();
    }

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void v5$updateMouse(CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void v5$onMouseScroll(long window, double horizontalScroll, double verticalScroll, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (V5MixinStorage.getBoolean("freelookEnabled", false) && client.screen == null) {
            Object storedDistance = V5MixinStorage.get("freelookCameraDistance", 4.0);
            double distance = storedDistance instanceof Number number ? number.doubleValue() : 4.0;
            V5MixinStorage.set("freelookCameraDistance", Mth.clamp(distance - verticalScroll, 1.0, 12.0));
            ci.cancel();
            return;
        }

        if (!V5MixinStorage.getBoolean("inputLocked", false)) {
            return;
        }

        if (client != null && client.screen == null) {
            ci.cancel();
        }
    }
}
