package com.v5.mixins;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.v5.render.helper.TextureTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

  @Inject(method = "_bindTexture", at = @At("HEAD"), remap = false)
  private static void onBindTexture(int texture, CallbackInfo ci) {
    TextureTracker.setPrevBoundTexture(texture);
  }

  @Inject(method = "_activeTexture", at = @At("HEAD"), remap = false)
  private static void onActiveTexture(int texture, CallbackInfo ci) {
    TextureTracker.setPrevActiveTexture(texture);
  }

}