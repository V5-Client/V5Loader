package com.v5.mixins;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {
    @Shadow
    protected int width;

    @Shadow
    protected int height;

    @Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
    private void v5$renderPanoramaShader(DrawContext context, float deltaTicks, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();

        double scaledMouseX = client.mouse.getX() * (double) width / (double) window.getWidth();
        double scaledMouseY = client.mouse.getY() * (double) height / (double) window.getHeight();
    }
}
