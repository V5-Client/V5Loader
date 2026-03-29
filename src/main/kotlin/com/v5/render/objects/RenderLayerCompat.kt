package com.v5.render.objects

import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.render.RenderLayer
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object RenderLayerCompat {
    private const val DEFAULT_BUFFER_SIZE = 1536

    @JvmStatic
    fun createLayer(
        name: String,
        pipeline: RenderPipeline,
        translucent: Boolean = false,
        viewOffset: Boolean = false
    ): RenderLayer {
        createModernLayer(name, pipeline, translucent, viewOffset)?.let { return it }
        return createLegacyLayer(name, pipeline, translucent, viewOffset)
    }

    private fun createModernLayer(
        name: String,
        pipeline: RenderPipeline,
        translucent: Boolean,
        viewOffset: Boolean
    ): RenderLayer? {
        val renderSetupClass = runCatching {
            Class.forName("net.minecraft.client.render.RenderSetup")
        }.getOrNull() ?: return null

        val builderMethod = renderSetupClass.methods.firstOrNull {
            it.name == "builder" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == RenderPipeline::class.java
        } ?: return null

        return runCatching {
            val builder = builderMethod.invoke(null, pipeline)
            callIfPresent(builder, "expectedBufferSize", DEFAULT_BUFFER_SIZE)

            if (viewOffset) {
                val layeringTransformClass = Class.forName("net.minecraft.client.render.LayeringTransform")
                val transform = layeringTransformClass.getField("VIEW_OFFSET_Z_LAYERING").get(null)
                callIfPresent(builder, "layeringTransform", transform)
            }

            if (translucent) {
                callIfPresent(builder, "translucent")
            }

            val setup = requireMethod(builder.javaClass, "build", 0).invoke(builder)
            val ofMethod = findRenderLayerOfMethod(2, setup.javaClass)
                ?: error("Unable to find modern RenderLayer.of(String, RenderSetup)")
            ofMethod.isAccessible = true
            ofMethod.invoke(null, name, setup) as RenderLayer
        }.getOrNull()
    }

    private fun createLegacyLayer(
        name: String,
        pipeline: RenderPipeline,
        translucent: Boolean,
        viewOffset: Boolean
    ): RenderLayer {
        val params = createLegacyMultiPhaseParameters(viewOffset)
        val paramsClass = params.javaClass

        val multiPhaseMethod = findRenderLayerOfMethod(
            paramCount = 6,
            finalParamClass = paramsClass
        )
        if (multiPhaseMethod != null) {
            multiPhaseMethod.isAccessible = true
            return multiPhaseMethod.invoke(
                null,
                name,
                DEFAULT_BUFFER_SIZE,
                false,
                translucent,
                pipeline,
                params
            ) as RenderLayer
        }

        val simpleMethod = findRenderLayerOfMethod(
            paramCount = 4,
            finalParamClass = paramsClass
        ) ?: error("Unable to find legacy RenderLayer.of overload")

        simpleMethod.isAccessible = true
        return simpleMethod.invoke(
            null,
            name,
            DEFAULT_BUFFER_SIZE,
            pipeline,
            params
        ) as RenderLayer
    }

    private fun createLegacyMultiPhaseParameters(viewOffset: Boolean): Any {
        val paramsClass = Class.forName("net.minecraft.client.render.RenderLayer\$MultiPhaseParameters")
        val builder = paramsClass.getMethod("builder").invoke(null)

        if (viewOffset) {
            val renderPhaseClass = Class.forName("net.minecraft.client.render.RenderPhase")
            val layeringValue = renderPhaseClass.getField("VIEW_OFFSET_Z_LAYERING").get(null)
            callIfPresent(builder, "layering", layeringValue)
        }

        val buildMethod = requireMethod(builder.javaClass, "build", 1)
        return buildMethod.invoke(builder, false)
    }

    private fun findRenderLayerOfMethod(paramCount: Int, finalParamClass: Class<*>): Method? {
        return RenderLayer::class.java.declaredMethods.firstOrNull {
            it.name == "of" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == paramCount &&
                it.parameterTypes.firstOrNull() == String::class.java &&
                it.parameterTypes.lastOrNull()?.isAssignableFrom(finalParamClass) == true
        }
    }

    private fun requireMethod(type: Class<*>, name: String, parameterCount: Int): Method {
        return type.methods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
            ?: type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
            ?: error("Missing method $name/$parameterCount on ${type.name}")
    }

    private fun callIfPresent(target: Any, name: String, vararg args: Any?) {
        val method = (target.javaClass.methods + target.javaClass.declaredMethods).firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == args.size &&
                candidate.parameterTypes.withIndex().all { (index, type) ->
                    val arg = args[index]
                    arg == null || wrapPrimitive(type).isInstance(arg)
                }
        } ?: return

        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun wrapPrimitive(type: Class<*>): Class<*> {
        return when (type) {
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
    }
}
