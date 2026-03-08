package com.v5.mixins;

import com.v5.render.ShaderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RotatingCubeMapRenderer.class)
public class RotatingCubeMapRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void v5$renderPanoramaShader(DrawContext context, int width, int height, boolean spin, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();

        double scaledMouseX = client.mouse.getX() * (double) width / (double) window.getWidth();
        double scaledMouseY = client.mouse.getY() * (double) height / (double) window.getHeight();

        if (ShaderUtils.INSTANCE.renderBackground(width, height, scaledMouseX, scaledMouseY)) {
            ci.cancel();
        }
    }
}
