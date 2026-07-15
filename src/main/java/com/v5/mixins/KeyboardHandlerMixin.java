package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void v5$cancelFreecamMovement(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!V5MixinStorage.getBoolean("freecamEnabled", false) || client.screen != null) {
            return;
        }

        Options options = client.options;
        if (options.keyUp.matches(event)
                || options.keyDown.matches(event)
                || options.keyLeft.matches(event)
                || options.keyRight.matches(event)
                || options.keyJump.matches(event)
                || options.keyShift.matches(event)
                || options.keySprint.matches(event)) {
            ci.cancel();
        }
    }
}
