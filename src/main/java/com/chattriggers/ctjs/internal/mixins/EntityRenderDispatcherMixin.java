package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.client.Client;
import com.chattriggers.ctjs.internal.engine.CTEvents;
import com.chattriggers.ctjs.api.render.Renderer;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderManager.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "reload", at = @At("TAIL"))
    private void injectReload(ResourceManager manager, CallbackInfo ci, @Local EntityRendererFactory.Context context) {
        Renderer.initializePlayerRenderers$ctjs(context);
    }

    @Inject(
        method = "render",
        at = @At("HEAD"),
        cancellable = true
    )
    private <S extends EntityRenderState> void injectRender(
        S state,
        CameraRenderState cameraState,
        double x,
        double y,
        double z,
        MatrixStack matrices,
        OrderedRenderCommandQueue queue,
        CallbackInfo ci
    ) {
        int id = ((EntityRenderStateAccessor) state).ctjs$getEntityId();
        if (id != 0 && Client.getMinecraft().world != null) {
            Entity entity = Client.getMinecraft().world.getEntityById(id);
            if (entity != null) {
                CTEvents.RENDER_ENTITY.invoker().render(
                    matrices,
                    entity,
                    Client.getMinecraft().getRenderTickCounter().getDynamicDeltaTicks(),
                    ci
                );
            }
        }
    }
}
