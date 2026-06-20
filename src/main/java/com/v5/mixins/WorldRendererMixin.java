package com.v5.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.v5.storage.V5MixinStorage;
import net.minecraft.client.render.*;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.Handle;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

  @Inject(method = "method_62214", at = @At("HEAD"), cancellable = true)
  private void v5$renderMain(GpuBufferSlice gpuBufferSlice, WorldRenderState worldRenderState, Profiler profiler, Matrix4f matrix4f, Handle handle, Handle handle2, boolean bl, Handle handle3, Handle handle4, CallbackInfo ci) {
    if (V5MixinStorage.getBoolean("macroEnabled", false)
            && "No Render".equals(V5MixinStorage.getString("renderLimiter", "Off"))) {
      ci.cancel();
    }
  }
}
