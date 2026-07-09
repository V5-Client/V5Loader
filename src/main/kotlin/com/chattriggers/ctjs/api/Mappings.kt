package com.chattriggers.ctjs.api

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.lib.mappingio.MappingReader
import net.fabricmc.loader.impl.lib.mappingio.tree.MemoryMappingTree
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree.ElementMapping
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree.MethodArgMapping
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTreeView
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.spongepowered.asm.mixin.transformer.ClassInfo
import org.spongepowered.asm.service.MixinService
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Allows runtime inspection of mappings
 */
object Mappings {
    // If this is changed, also change the Java.type function in mixinProvidedLibs.js
    internal val mappedPackages = setOf("Lnet/minecraft/", "Lcom/mojang/blaze3d/")

    private val unmappedClasses = mutableMapOf<String, MappedClass>()
    private val mappedToUnmappedClassNames = mutableMapOf<String, String>()
    private val runtimeNamespace by lazy { detectRuntimeNamespace() }
    private const val SCRIPT_NAMESPACE = "named"
    private const val EMBEDDED_MAPPINGS = "/ctjs-mappings.tiny"

    internal fun initialize() {
        val tree: MappingTree = loadMappingTree()

        tree.classes.forEach { clazz ->
            val fields = mutableMapOf<String, MappedField>()

            clazz.fields.forEach { field ->
                fields[field.unmappedName] = MappedField(
                    name = Mapping.fromMapped(field),
                    type = Mapping(field.unmappedType.descriptor, field.mappedType.descriptor)
                )
            }

            val methods = mutableMapOf<String, MutableList<MappedMethod>>()

            clazz.methods.forEach { method ->
                val unmappedType = method.unmappedType
                val mappedType = method.mappedType

                methods.getOrPut(method.unmappedName, ::mutableListOf).add(
                    MappedMethod(
                        name = Mapping.fromMapped(method),
                        parameters = method.args.sortedBy { it.lvIndex }.mapIndexed { index, param ->
                            // Runtime mappings often omit argument names; a stable placeholder is good enough here.
                            val paramName = param.namedName ?: param.srcName ?: "arg${param.argPosition}"
                            MappedParameter(
                                Mapping(paramName, param.mappedName),
                                Mapping(
                                    unmappedType.argumentTypes[index].descriptor,
                                    mappedType.argumentTypes[index].descriptor,
                                ),
                                param.lvIndex,
                            )
                        },
                        returnType = Mapping(unmappedType.returnType.descriptor, mappedType.returnType.descriptor)
                    )
                )
            }

            unmappedClasses[clazz.unmappedName] = MappedClass(
                name = Mapping.fromMapped(clazz),
                fields,
                methods
            )

            classAliases(clazz).forEach {
                mappedToUnmappedClassNames[it] = clazz.unmappedName
            }
        }
    }

    private fun loadMappingTree(): MappingTree {
        val mappings = javaClass.getResourceAsStream(EMBEDDED_MAPPINGS) ?: return FabricLauncherBase.getLauncher().mappingConfiguration.mappings
        val tree = MemoryMappingTree()

        InputStreamReader(mappings, StandardCharsets.UTF_8).use {
            MappingReader.read(it, tree)
        }

        return tree
    }

    private fun detectRuntimeNamespace(): String {
        val launcher = FabricLauncherBase.getLauncher()

        fun hasClass(path: String) = launcher.getResourceAsStream(path)?.use { true } == true

        return when {
            hasClass("net/minecraft/client/Minecraft.class") -> SCRIPT_NAMESPACE
            hasClass("net/minecraft/class_310.class") -> "intermediary"
            else -> launcher.mappingConfiguration.runtimeNamespace
        }
    }

    internal fun getMappedClass(unmappedClassName: String): MappedClass? {
        var name = normalizeClassName(unmappedClassName)
        mappedToUnmappedClassNames[name]?.also { name = it }
        return unmappedClasses[name]
    }

    internal fun getUnmappedClass(unmappedClassName: String): MappedClass {
        val name = normalizeClassName(unmappedClassName)
        val classNode = MixinService.getService().bytecodeProvider.getClassNode(unmappedClassName)

        val fields = classNode.fields.associate {
            val type = it.desc
            val fieldName = it.name

            fieldName to MappedField(Mapping(fieldName, fieldName), Mapping(type, mapDescriptor(type)))
        }

        val methods = mutableMapOf<String, MutableList<MappedMethod>>()
        for (method in classNode.methods) {
            val isStatic = method.access and Opcodes.ACC_STATIC != 0
            var lvtIndex = if (isStatic) 0 else 1

            val params = mutableListOf<MappedParameter>()
            Type.getArgumentTypes(method.desc).forEachIndexed { index, type ->
                val paramType = type.descriptor
                val paramName = method.parameters?.get(index)?.name ?: return@forEachIndexed

                params.add(
                    MappedParameter(
                        Mapping(paramName, paramName),
                        Mapping(paramType, mapDescriptor(paramType)),
                        lvtIndex
                    )
                )

                if (type == Type.DOUBLE_TYPE || type == Type.LONG_TYPE) {
                    lvtIndex += 2
                } else {
                    lvtIndex++
                }
            }

            val returnType = Type.getReturnType(method.desc).descriptor
            val methodName = method.name
            methods.getOrPut(methodName, ::mutableListOf).add(
                MappedMethod(
                    Mapping(methodName, methodName),
                    params,
                    Mapping(returnType, mapDescriptor(returnType))
                )
            )
        }

        mappedToUnmappedClassNames[name] = name
        return MappedClass(Mapping(name, name), fields, methods).also {
            unmappedClasses[name] = it
        }
    }

    internal fun getMappedClassName(unmappedClassName: String) = getMappedClass(unmappedClassName)?.name?.value

    /**
     * Gets a classes unmapped class name, or throws an error if it is not mapped
     */
    @JvmStatic
    fun unmapClass(clazz: Class<*>) = unmapClassName(clazz.name)

    /**
     * Gets an unmapped class name from a mapped class name, or returns null if
     * it either does not exist or is not mapped.
     */
    @JvmStatic
    fun unmapClassName(className: String): String? {
        return mappedToUnmappedClassNames[normalizeClassName(className)]
    }

    /**
     * Gets the mapped class name from an unmapped class name or null if the class
     * name does not exist. Note that this is not required to use mapped classes,
     * as Rhino performs this mapping automatically during runtime.
     */
    @JvmStatic
    fun mapClassName(className: String) = getMappedClassName(className)

    private fun mapDescriptor(descriptor: String) = mapClassName(descriptor) ?: descriptor

    private fun normalizeClassName(className: String) = (if (className.startsWith('L') && className.endsWith(';')) {
        className.drop(1).dropLast(1)
    } else className).replace('.', '/')

    internal data class Mapping(val original: String, val mapped: String) {
        val value: String
            get() = if (FabricLoader.getInstance().isDevelopmentEnvironment) original else mapped

        companion object {
            fun fromMapped(mapped: ElementMapping) = Mapping(mapped.unmappedName, mapped.mappedName)
        }
    }

    internal data class MappedField(val name: Mapping, val type: Mapping)

    internal class MappedParameter(
        val name: Mapping,
        val type: Mapping,
        val lvtIndex: Int,
    )

    internal class MappedMethod(
        val name: Mapping,
        val parameters: List<MappedParameter>,
        val returnType: Mapping,
    ) {
        fun toDescriptor() = buildString {
            append('(')
            parameters.forEach {
                append(it.type.value)
            }
            append(')')
            append(returnType.value)
        }

        fun toFullDescriptor() = name.value + toDescriptor()
    }

    internal class MappedClass(
        val name: Mapping,
        val fields: Map<String, MappedField>,
        val methods: Map<String, List<MappedMethod>>,
    ) {
        fun findMethods(name: String, classInfo: ClassInfo?): List<MappedMethod>? {
            methods[name]?.let { return it }

            if (classInfo == null)
                return null

            val unmappedSuperClass = mappedToUnmappedClassNames[classInfo.superName]
            if (unmappedSuperClass != null) {
                return unmappedClasses[unmappedSuperClass]?.findMethods(name, classInfo.superClass)
            }

            val methods = mutableListOf<MappedMethod>()
            for (itf in classInfo.interfaces) {
                val unmappedInterface = mappedToUnmappedClassNames[itf] ?: continue
                unmappedClasses[unmappedInterface]?.findMethods(name, null)?.let { methods += it }
            }

            return if (methods.isEmpty()) null else methods
        }
    }

    private val ElementMapping.unmappedName: String
        get() = getName(SCRIPT_NAMESPACE) ?: srcName

    private val ElementMapping.mappedName: String
        get() = getName(runtimeNamespace) ?: srcName

    private fun classAliases(clazz: ElementMapping) = listOfNotNull(
        clazz.srcName,
        clazz.unmappedName,
        clazz.mappedName,
        clazz.getName("official"),
        clazz.getName("intermediary"),
        clazz.getName("named"),
    ).distinct()

    // Parameters often have no runtime name mapping at all.
    private val MethodArgMapping.mappedName: String
        get() = getName(runtimeNamespace) ?: namedName ?: srcName ?: "arg${argPosition}"

    private val MethodArgMapping.namedName: String?
        get() = getName(SCRIPT_NAMESPACE)

    private val MappingTreeView.MemberMappingView.unmappedType: Type
        get() = Type.getType(tree.getNamespaceId(SCRIPT_NAMESPACE).takeIf { it >= 0 }?.let(::getDesc) ?: srcDesc)

    private val MappingTreeView.MemberMappingView.mappedType: Type
        get() = Type.getType(tree.getNamespaceId(runtimeNamespace).takeIf { it >= 0 }?.let(::getDesc) ?: srcDesc)
}
