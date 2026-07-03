package com.v5.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.chattriggers.ctjs.api.render.NVGRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        NVGRenderer.runPreDrawables();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        NVGRenderer.runDrawables();
    }
}
