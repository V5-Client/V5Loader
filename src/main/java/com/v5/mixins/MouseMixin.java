package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mouse.class)
public class MouseMixin {
    @Inject(method = "isCursorLocked()Z", at = @At("HEAD"), cancellable = true)
    private void v5$isCursorLocked(CallbackInfoReturnable<Boolean> cir) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "lockCursor()V", at = @At("HEAD"), cancellable = true)
    private void v5$lockCursor(CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateMouse(D)V", at = @At("HEAD"), cancellable = true)
    private void v5$updateMouse(CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void v5$onMouseScroll(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("inputLocked", false)) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen == null) {
            ci.cancel();
        }
    }
}
