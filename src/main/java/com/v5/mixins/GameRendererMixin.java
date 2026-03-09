package com.v5.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.v5.render.NVGRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @ModifyExpressionValue(
            method = "render",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/option/GameOptions;pauseOnLostFocus:Z"))
    private boolean v5$render(boolean original) {
        return false;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        NVGRenderer.runDrawables();
    }
}
