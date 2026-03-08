package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.client.Client;
import com.chattriggers.ctjs.api.render.Renderer;
import com.chattriggers.ctjs.internal.IRenderState;
import com.chattriggers.ctjs.internal.engine.CTEvents;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderManager.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "reload", at = @At("TAIL"))
    private void injectReload(net.minecraft.resource.ResourceManager manager, CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local net.minecraft.client.render.entity.EntityRendererFactory.Context context) {
        Renderer.initializePlayerRenderers$ctjs(context);
    }

    @ModifyExpressionValue(
        method = "getAndUpdateRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderer;getAndUpdateRenderState(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/client/render/entity/state/EntityRenderState;")
    )
    private EntityRenderState attachEntityToState(EntityRenderState state, Entity entity, float tickProgress) {
        ((IRenderState) state).ctjs$setEntity(entity);
        return state;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <S extends EntityRenderState> void injectRender(
        S renderState,
        CameraRenderState cameraRenderState,
        double d, double e, double f,
        MatrixStack matrixStack,
        OrderedRenderCommandQueue orderedRenderCommandQueue,
        CallbackInfo ci
    ) {
        Entity entity = ((IRenderState) renderState).ctjs$getEntity();

        if (entity != null) {
            float partialTicks = Client.getMinecraft().getRenderTickCounter().getDynamicDeltaTicks();
            CTEvents.RENDER_ENTITY.invoker().render(matrixStack, entity, partialTicks, ci);
        }
    }
}
