package com.v5.mixins;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import com.v5.render.helper.DrawContextHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class DrawContextCapture {
    @Inject(method = "render", at = @At("HEAD"))
    private void captureContext(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        DrawContextHolder.setCurrentContext(context);
    }
}