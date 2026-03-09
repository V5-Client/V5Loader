package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void v5$getDisplayName(CallbackInfoReturnable<Text> cir) {
        Text original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        Text updated = V5MixinStorage.applyMethod("nameProcessor", original, Text.class);
        if (updated != original) {
            cir.setReturnValue(updated);
        }
    }
}
