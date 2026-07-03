package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.internal.listeners.WorldListener;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    private float ctjs$tickDelta = 1.0F;

    @Inject(
        method = "renderBlockOutline",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/level/CameraRenderState;pos:Lnet/minecraft/world/phys/Vec3;"
        ),
        cancellable = true,
        locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void onDrawBlockOutline(MultiBufferSource.BufferSource immediate, PoseStack matrices, boolean renderBlockOutline, LevelRenderState renderStates, CallbackInfo ci, BlockOutlineRenderState outlineRenderState) {
        if (WorldListener.INSTANCE.triggerBlockOutline(outlineRenderState.pos()))
            ci.cancel();
    }

    @ModifyExpressionValue(
        method = "lambda$addMainPass$0",
        at = @At(value = "NEW", target = "()Lcom/mojang/blaze3d/vertex/PoseStack;")
    )
    private PoseStack onMatrixStack(PoseStack original) {
        WorldListener.INSTANCE.setMatrixStack(original);
        WorldListener.INSTANCE.triggerRenderStart(ctjs$tickDelta);
        return original;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void beforeRender(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline, CameraRenderState camera, Matrix4fc positionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        ctjs$tickDelta = tickCounter.getGameTimeDeltaTicks();
    }

    @Inject(method = "lambda$addMainPass$0", at = @At("RETURN"))
    private void afterRender(GpuBufferSlice gpuBufferSlice, LevelRenderState worldRenderState, ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender, ResourceHandle handle, ResourceHandle handle2, ResourceHandle handle3, ResourceHandle handle4, ResourceHandle handle5, boolean bl, Matrix4fc matrix4f, CallbackInfo ci) {
        WorldListener.INSTANCE.triggerRenderLast();
    }
}
