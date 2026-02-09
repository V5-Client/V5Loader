package com.v5.mixins;

import net.minecraft.client.resource.language.TranslationStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TranslationStorage.class, priority = 1000)
public class TranslationMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void onGet(String key, String fallback, CallbackInfoReturnable<String> cir) {
        if (key.equals("key.category.v5.v5_modules")) {
            cir.setReturnValue("V5");
            return;
        }

        if (key.startsWith("v5.key.")) {
            String humanReadable = key.substring("v5.key.".length());
            cir.setReturnValue(humanReadable);
        }
    }
}