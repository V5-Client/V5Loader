package com.chattriggers.ctjs.internal.mixins;


import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements IRenderState {
    @Unique
    private Entity ctjs$entity;

    @Override
    public void ctjs$setEntity(Entity entity) {
        this.ctjs$entity = entity;
    }

    @Override
    public Entity ctjs$getEntity() {
        return this.ctjs$entity;
    }
}