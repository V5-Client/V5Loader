package com.v5.mixins;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.DeltaTracker;
import com.chattriggers.ctjs.api.render.DrawContextHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class DrawContextCapture {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void captureContext(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        DrawContextHolder.setCurrentContext(context);
    }
}
