package com.chattriggers.ctjs.internal;

import net.minecraft.entity.Entity;

public interface IRenderState {
    void ctjs$setEntity(Entity entity);
    Entity ctjs$getEntity();
}