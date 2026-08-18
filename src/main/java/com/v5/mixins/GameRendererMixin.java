package com.v5.mixins;

import com.chattriggers.ctjs.api.render.NVGRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(CallbackInfo ci) {
        NVGRenderer.runPreDrawables();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(CallbackInfo ci) {
        NVGRenderer.runDrawables();
    }
}
