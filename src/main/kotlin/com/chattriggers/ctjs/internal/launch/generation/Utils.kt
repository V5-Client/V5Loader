package com.chattriggers.ctjs.internal.launch.generation

import com.chattriggers.ctjs.api.Mappings
import com.chattriggers.ctjs.internal.launch.At
import com.chattriggers.ctjs.internal.launch.Constant
import com.chattriggers.ctjs.internal.launch.Descriptor
import com.chattriggers.ctjs.internal.launch.Local
import com.chattriggers.ctjs.internal.launch.Slice
import com.chattriggers.ctjs.internal.utils.descriptorString
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.fabricmc.loader.impl.lib.classtweaker.api.visitor.AccessWidenerVisitor
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.spongepowered.asm.mixin.transformer.ClassInfo
import org.spongepowered.asm.mixin.injection.At as SPAt
import org.spongepowered.asm.mixin.injection.Constant as SPConstant
import org.spongepowered.asm.mixin.injection.Slice as SPSlice

internal object Utils {
    fun createAtAnnotation(at: At): AnnotationNode {
        return AnnotationNode(SPAt::class.descriptorString()).apply {
            visitOptional("id", at.id)
            visit("value", at.value)
            visitOptional("slice", at.slice)
            visitOptional("shift", at.shift?.let { arrayOf(SPAt.Shift::class.java.descriptorString(), it.name) })
            visitOptional("by", at.by)
            visitOptional("args", at.args)
            visitOptional("target", at.target?.let { at.atTarget.descriptor.mappedDescriptor() })
            visitOptional("ordinal", at.ordinal)
            visitOptional("opcode", at.opcode)
            visitOptional("remap", at.remap)

            visitEnd()
        }
    }

    fun createSliceAnnotation(slice: Slice): AnnotationNode {
        return AnnotationNode(SPSlice::class.descriptorString()).apply {
            visitOptional("id", slice.id)
            visitOptional("from", slice.from?.let(::createAtAnnotation))
            visitOptional("to", slice.to?.let(::createAtAnnotation))
            visitEnd()
        }
    }

    fun createConstantAnnotation(constant: Constant): AnnotationNode {
        return AnnotationNode(SPConstant::class.descriptorString()).apply {
            visitOptional("nullValue", constant.nullValue)
            visitOptional("intValue", constant.intValue)
            visitOptional("floatValue", constant.floatValue)
            visitOptional("longValue", constant.longValue)
            visitOptional("doubleValue", constant.doubleValue)
            visitOptional("stringValue", constant.stringValue)
            if (constant.classValue != null) {
                val name = Mappings.getMappedClassName(constant.classValue)
                    ?: error("Unknown class \"${constant.classValue}\"")
                visit("classValue", Type.getObjectType(name))
            }
            visitOptional("ordinal", constant.ordinal)
            visitOptional("slice", constant.slice)
            visitOptional("expandZeroConditions", constant.expandZeroConditions)
            visitOptional("log", constant.log)
        }
    }

    fun widenField(mappedClass: Mappings.MappedClass, fieldName: String, isMutable: Boolean) {
        val field = mappedClass.fields[fieldName]
            ?: error("Unable to find field $fieldName in class ${mappedClass.name.original}")
        val widener = requireNotNull(FabricLoaderImpl.INSTANCE.classTweaker.visitAccessWidener(mappedClass.name.value))

        widener.visitField(
            field.name.value,
            field.type.value,
            AccessWidenerVisitor.AccessType.ACCESSIBLE,
            false,
        )

        if (isMutable) {
            widener.visitField(
                field.name.value,
                field.type.value,
                AccessWidenerVisitor.AccessType.MUTABLE,
                false,
            )
        }
    }

    fun widenMethod(
        mappedClass: Mappings.MappedClass,
        methodName: String,
        isMutable: Boolean,
    ) {
        val descriptor = Descriptor.Parser(methodName).parseMethod(full = false)
        val mappedMethod = findMethod(mappedClass, descriptor).first
        val widener = requireNotNull(FabricLoaderImpl.INSTANCE.classTweaker.visitAccessWidener(mappedClass.name.value))

        widener.visitMethod(
            mappedMethod.name.value,
            mappedMethod.toDescriptor(),
            AccessWidenerVisitor.AccessType.ACCESSIBLE,
            false,
        )

        if (isMutable) {
            widener.visitMethod(
                mappedMethod.name.value,
                mappedMethod.toDescriptor(),
                AccessWidenerVisitor.AccessType.MUTABLE,
                false,
            )
        }
    }

    fun findMethod(
        mappedClass: Mappings.MappedClass,
        descriptor: Descriptor.Method,
    ): Pair<Mappings.MappedMethod, ClassInfo.Method> {
        val parameters = descriptor.parameters

        val classInfo = ClassInfo.forName(mappedClass.name.value)
        val mappedMethods = mappedClass.findMethods(descriptor.name, classInfo)
            ?: error("Cannot find method ${descriptor.name} in class ${mappedClass.name.original}")

        var value: Pair<Mappings.MappedMethod, ClassInfo.Method>? = null

        for (method in mappedMethods) {
            if (parameters != null) {
                if (method.parameters.size != parameters.size)
                    continue

                if (method.parameters.zip(parameters).any { it.first.type.original != it.second.originalDescriptor() })
                    continue
            }

            val result = classInfo.findMethodInHierarchy(
                method.name.value,
                method.toDescriptor(),
                ClassInfo.SearchType.ALL_CLASSES,
                ClassInfo.INCLUDE_ALL or ClassInfo.INCLUDE_INITIALISERS,
            ) ?: continue

            if (value != null)
                error(
                    "Multiple methods match name ${descriptor.name} in class ${mappedClass.name.original}, please " +
                        "provide a method descriptor"
                )

            value = method to result
        }

        if (value != null)
            return value

        error("Unable to match method $descriptor in class ${mappedClass.name.original}")
    }

    fun getParameterFromLocal(local: Local, name: String = "Local"): InjectorGenerator.Parameter {
        val descriptor = when {
            local.print == true -> {
                // The type doesn't matter, it won't actually be applied
                Descriptor.Primitive.INT
            }
            local.type != null -> {
                if (local.index != null) {
                    require(local.ordinal == null) {
                        "$name that specifies a type and index cannot specify an ordinal"
                    }
                } else {
                    require(local.ordinal != null) {
                        "$name that specifies a type must also specify an index or ordinal"
                    }
                }
                Descriptor.Parser(local.type).parseType(full = true)
            }
            else -> error("$name must specify \"print\", or \"type\" and either \"ordinal\" or \"index\"")
        }

        require(descriptor.isType)

        return InjectorGenerator.Parameter(descriptor, local)
    }

}

internal fun AnnotationVisitor.visitOptional(name: String, value: Any?) {
    if (value != null)
        visit(name, value)
}

internal fun MutableList<InjectorGenerator.Parameter>.addLocals(locals: List<Local>?) {
    locals?.mapTo(this) { Utils.getParameterFromLocal(it) }
}
