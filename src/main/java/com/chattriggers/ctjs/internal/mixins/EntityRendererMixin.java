package com.chattriggers.ctjs.internal.mixins;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.chattriggers.ctjs.internal.mixins.EntityRenderStateAccessor;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<E extends Entity, S extends EntityRenderState> {
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void populateEntityId(E entity, S state, float tickDelta, CallbackInfo ci) {
        ((EntityRenderStateAccessor) state).ctjs$setEntityId(entity.getId());
    }
}
