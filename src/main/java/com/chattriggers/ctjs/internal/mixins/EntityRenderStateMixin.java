package com.chattriggers.ctjs.internal.mixins;

import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityRenderStateAccessor {
    @Unique
    private int ctjs$entityId;

    @Override
    public void ctjs$setEntityId(int id) {
        this.ctjs$entityId = id;
    }

    @Override
    public int ctjs$getEntityId() {
        return this.ctjs$entityId;
    }
}