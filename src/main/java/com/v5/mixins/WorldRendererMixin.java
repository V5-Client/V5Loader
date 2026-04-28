package com.v5.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.v5.storage.V5MixinStorage;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.v5.event.Context;
import com.v5.event.WorldRenderEvent;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.Handle;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

  @Shadow
  @Final
  private BufferBuilderStorage bufferBuilders;

  @Unique
  private Context ctx;

  @Unique
  private Context getCtx() {
    if (ctx == null) {
      ctx = new Context();
    }
    return ctx;
  }

  @Inject(method = "render", at = @At("HEAD"))
  private void render(ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f matrix4f, Matrix4f projectionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
    Context c = getCtx();
    c.setConsumers(bufferBuilders.getEntityVertexConsumers());
    c.setCamera(camera);
    WorldRenderEvent.START.invoker().trigger(c);
  }

  @Inject(method = "method_62214", at = @At("RETURN"))
  private void postRender(GpuBufferSlice gpuBufferSlice, WorldRenderState worldRenderState, Profiler profiler, Matrix4f matrix4f, Handle handle, Handle handle2, boolean bl, Handle handle3, Handle handle4, CallbackInfo ci) {
    Context c = getCtx();

    Frustum frustum = MinecraftClient.getInstance().worldRenderer.getCapturedFrustum();

    if (frustum != null) {
      c.setFrustum(frustum);
    }

    WorldRenderEvent.LAST.invoker().trigger(c);
  }

  @ModifyExpressionValue(method = "method_62214", at = @At(value = "NEW", target = "()Lnet/minecraft/client/util/math/MatrixStack;"))
  private MatrixStack setInternalStack(MatrixStack original) {
    getCtx().setMatrixStack(original);
    return original;
  }

  @Inject(method = "method_62214", at = @At("HEAD"), cancellable = true)
  private void v5$renderMain(GpuBufferSlice gpuBufferSlice, WorldRenderState worldRenderState, Profiler profiler, Matrix4f matrix4f, Handle handle, Handle handle2, boolean bl, Handle handle3, Handle handle4, CallbackInfo ci) {
    if (V5MixinStorage.getBoolean("macroEnabled", false)
            && "No Render".equals(V5MixinStorage.getString("renderLimiter", "Off"))) {
      ci.cancel();
    }
  }
}
