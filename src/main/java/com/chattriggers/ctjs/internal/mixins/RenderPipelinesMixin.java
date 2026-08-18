package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.render.RenderLayers;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(RenderPipelines.class)
public class RenderPipelinesMixin {
    @Inject(method = "getStaticPipelines", at = @At("HEAD"))
    private static void ctjs$registerEspPipelines(CallbackInfoReturnable<List<RenderPipeline>> cir) {
        RenderLayers.ensureRegistered();
    }
}
