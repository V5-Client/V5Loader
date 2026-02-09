package com.v5.render.helper

import net.minecraft.client.render.Frustum

object FrustumHolder {

    @Volatile
    @JvmField
    var currentFrustum: Frustum? = null

}