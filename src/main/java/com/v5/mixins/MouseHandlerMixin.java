package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "isMouseGrabbed()Z", at = @At("HEAD"), cancellable = true)
    private void v5$isCursorLocked(CallbackInfoReturnable<Boolean> cir) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "grabMouse()V", at = @At("HEAD"), cancellable = true)
    private void v5$lockCursor(CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void v5$updateMouse(CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("ungrabbed", false)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void v5$onMouseScroll(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("inputLocked", false)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.screen == null) {
            ci.cancel();
        }
    }
}
