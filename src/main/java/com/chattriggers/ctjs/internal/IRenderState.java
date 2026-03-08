package com.chattriggers.ctjs.internal.mixins;

import net.minecraft.entity.Entity;

public interface IRenderState {
    void ctjs$setEntity(Entity entity);
    Entity ctjs$getEntity();
}