package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void v5$getDisplayName(CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        Component updated = V5MixinStorage.applyMethod("nameProcessor", original, Component.class);
        if (updated != original) {
            cir.setReturnValue(updated);
        }
    }
}
