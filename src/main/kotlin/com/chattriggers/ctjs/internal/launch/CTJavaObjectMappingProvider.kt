package com.chattriggers.ctjs.internal.launch

import com.chattriggers.ctjs.api.Mappings
import org.mozilla.javascript.JavaObjectMappingProvider
import java.lang.reflect.Modifier

object CTJavaObjectMappingProvider : JavaObjectMappingProvider {
    override fun mapClassName(className: String) =
        if (isMappedClassName(className)) Mappings.mapClassName(className)?.replace('/', '.') else null

    override fun unmapClassName(className: String) =
        if (isMappedClassName(className)) Mappings.unmapClassName(className)?.replace('/', '.') else null

    override fun findExtraMethods(
        clazz: Class<*>,
        map: MutableMap<JavaObjectMappingProvider.MethodSignature, JavaObjectMappingProvider.RenameableMethod>,
        includeProtected: Boolean,
        includePrivate: Boolean
    ) {
        val queue = ArrayDeque<Class<*>>()
        var current: Class<*>? = clazz

        while (current != null) {
            findRemappedMethods(
                current,
                map,
                includeProtected,
                includePrivate
            )

            val superClass = current.superclass
            if (superClass != null)
                queue.add(superClass)

            for (itf in current.interfaces)
                queue.add(itf)

            current = queue.removeFirstOrNull()
        }
    }

    override fun findExtraFields(
        clazz: Class<*>,
        list: MutableList<JavaObjectMappingProvider.RenameableField>,
        includeProtected: Boolean,
        includePrivate: Boolean
    ) {
        var current: Class<*>? = clazz
        while (current != null) {
            findRemappedFields(
                current,
                list,
                includeProtected,
                includePrivate
            )
            current = current.superclass
        }
    }

    private fun findRemappedMethods(
        clazz: Class<*>,
        map: MutableMap<JavaObjectMappingProvider.MethodSignature, JavaObjectMappingProvider.RenameableMethod>,
        includeProtected: Boolean,
        includePrivate: Boolean,
    ) {
        val mappedClass = Mappings.getMappedClass(clazz.name) ?: return

        for ((unmappedMethodName, mappedMethods) in mappedClass.methods) {
            for (mappedMethod in mappedMethods) {
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == mappedMethod.name.value && isIncluded(it.modifiers, includeProtected, includePrivate)
                } ?: continue

                map[JavaObjectMappingProvider.MethodSignature(unmappedMethodName, method.parameterTypes)] =
                    JavaObjectMappingProvider.RenameableMethod(method, unmappedMethodName)
            }
        }
    }

    private fun findRemappedFields(
        clazz: Class<*>,
        list: MutableList<JavaObjectMappingProvider.RenameableField>,
        includeProtected: Boolean,
        includePrivate: Boolean
    ) {
        val mappedClass = Mappings.getMappedClass(clazz.name) ?: return

        for ((unmappedFieldName, mappedField) in mappedClass.fields) {
            val field = clazz.declaredFields.firstOrNull {
                it.name == mappedField.name.value && isIncluded(it.modifiers, includeProtected, includePrivate)
            } ?: continue

            list.add(JavaObjectMappingProvider.RenameableField(field, unmappedFieldName))
        }
    }

    private fun isIncluded(modifiers: Int, includeProtected: Boolean, includePrivate: Boolean) = when {
        Modifier.isPublic(modifiers) -> true
        Modifier.isProtected(modifiers) -> includeProtected
        Modifier.isPrivate(modifiers) -> includePrivate
        else -> false
    }

    private fun isMappedClassName(className: String) =
        className.startsWith("net.minecraft.") || className.startsWith("com.mojang.blaze3d.")
}
