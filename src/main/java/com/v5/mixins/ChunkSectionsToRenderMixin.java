package com.v5.mixins;

import com.mojang.blaze3d.textures.GpuSampler;
import com.v5.storage.V5MixinStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void v5$skipTerrainWhenNoRender(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (V5MixinStorage.getBoolean("macroEnabled", false)
                && "No Render".equals(V5MixinStorage.getString("renderLimiter", "Off"))) {
            ci.cancel();
        }
    }
}
