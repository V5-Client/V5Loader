package com.v5.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.v5.storage.V5MixinStorage;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

  @Inject(method = "addMainPass", at = @At("HEAD"), cancellable = true)
  private void v5$renderMain(FrameGraphBuilder frameGraphBuilder, Frustum frustum, Matrix4fc matrix4f, GpuBufferSlice gpuBufferSlice, boolean renderBlockOutline, LevelRenderState worldRenderState, DeltaTracker deltaTracker, ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
    if (V5MixinStorage.getBoolean("macroEnabled", false)
            && "No Render".equals(V5MixinStorage.getString("renderLimiter", "Off"))) {
      ci.cancel();
    }
  }
}
