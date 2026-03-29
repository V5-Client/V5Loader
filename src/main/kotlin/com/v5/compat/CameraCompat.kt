package com.v5.compat

import net.minecraft.client.render.Camera
import net.minecraft.util.math.Vec3d
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object CameraCompat {
    private val getMethod: Method? = listOf("getCameraPos", "getPos").firstNotNullOfOrNull { name ->
        runCatching { Camera::class.java.getMethod(name) }.getOrNull()
    }?.apply { isAccessible = true }

    private val posField: Field? = listOf("cameraPos", "pos").firstNotNullOfOrNull { name ->
        runCatching { Camera::class.java.getDeclaredField(name) }.getOrNull()
    }?.apply { isAccessible = true }

    @JvmStatic
    fun getPos(camera: Camera): Vec3d {
        val method = getMethod
        if (method != null) {
            val value = method.invoke(camera)
            if (value is Vec3d) return value
        }

        val field = posField
        if (field != null) {
            val value = field.get(camera)
            if (value is Vec3d) return value
        }

        throw IllegalStateException("Unable to resolve camera position accessor for ${camera.javaClass.name}")
    }
}
