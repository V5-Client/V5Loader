package com.chattriggers.ctjs.internal.launch.generation

import codes.som.koffee.MethodAssembly
import codes.som.koffee.insns.jvm.*
import com.chattriggers.ctjs.api.Mappings
import com.chattriggers.ctjs.internal.launch.At
import com.chattriggers.ctjs.internal.launch.Descriptor
import com.chattriggers.ctjs.internal.launch.WrapOperation
import com.chattriggers.ctjs.internal.utils.descriptor
import com.chattriggers.ctjs.internal.utils.descriptorString
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodNode
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation as SPWrapOperation

internal class WrapOperationGenerator(
    ctx: GenerationContext,
    id: Int,
    private val wrapOperation: WrapOperation,
) : InjectorGenerator(ctx, id) {
    override val type = "wrapOperation"

    override fun getInjectionSignature(): InjectionSignature {
        val (mappedMethod, method) = ctx.findMethod(wrapOperation.method)

        val parameters = mutableListOf<Parameter>()
        val returnType: Descriptor

        if (wrapOperation.at == null) {
            require(wrapOperation.constant != null) {
                "WrapOperation must specify either 'at' or 'constant'"
            }
            require(wrapOperation.constant.classValue != null) {
                "WrapOperation targeting INSTANCEOF must specify constant.classValue"
            }

            parameters.add(Parameter(Any::class.descriptor()))
            parameters.add(Parameter(Operation::class.descriptor()))
            returnType = Descriptor.Primitive.BOOLEAN
        } else {
            when (val atTarget = wrapOperation.at.atTarget) {
                is At.InvokeTarget -> {
                    val descriptor = atTarget.descriptor

                    val targetClass = Mappings.getMappedClass(descriptor.owner!!.originalDescriptor())
                        ?: error("Unknown class ${descriptor.owner}")
                    val targetMethodIsStatic = Utils.findMethod(targetClass, descriptor).second.isStatic

                    if (!targetMethodIsStatic)
                        parameters.add(Parameter(descriptor.owner))

                    descriptor.parameters!!.forEach {
                        parameters.add(Parameter(it))
                    }
                    parameters.add(Parameter(Operation::class.descriptor()))
                    returnType = descriptor.returnType!!
                }
                is At.FieldTarget -> {
                    require(atTarget.isStatic != null && atTarget.isGet != null) {
                        "WrapOperation targeting FIELD expects an opcode value"
                    }

                    val descriptor = atTarget.descriptor
                    if (!atTarget.isStatic)
                        parameters.add(Parameter(descriptor.owner!!))

                    if (!atTarget.isGet)
                        parameters.add(Parameter(descriptor.type!!))

                    parameters.add(Parameter(Operation::class.descriptor()))

                    returnType = if (atTarget.isGet) {
                        descriptor.type!!
                    } else Descriptor.Primitive.VOID
                }
                is At.NewTarget -> {
                    atTarget.descriptor.parameters!!.forEach {
                        parameters.add(Parameter(it))
                    }
                    parameters.add(Parameter(Operation::class.descriptor()))
                    returnType = atTarget.descriptor.type
                }
                else -> error("Unexpected At.target for WrapOperation: ${atTarget.targetName}")
            }
        }

        parameters.addLocals(wrapOperation.locals)

        return InjectionSignature(
            mappedMethod,
            parameters,
            returnType,
            method.isStatic,
        )
    }

    override fun attachAnnotation(node: MethodNode, signature: InjectionSignature) {
        node.visitAnnotation(SPWrapOperation::class.descriptorString(), true).apply {
            visit("method", signature.targetMethod.toFullDescriptor())
            visitOptional("at", wrapOperation.at?.let(Utils::createAtAnnotation))
            visitOptional("constant", wrapOperation.constant?.let(Utils::createConstantAnnotation))
            visitOptional("slice", wrapOperation.slice?.map(Utils::createSliceAnnotation))
            visitOptional("remap", wrapOperation.remap)
            visitOptional("require", wrapOperation.require)
            visitOptional("expect", wrapOperation.expect)
            visitOptional("allow", wrapOperation.allow)
            visitEnd()
        }
    }

    context(MethodAssembly)
    override fun generateNotAttachedBehavior() {
        val operationType = Type.getType(Operation::class.java)
        val operationParameterIndex = signature.parameters.indexOfFirst {
            it.descriptor.toType() == operationType
        }
        check(operationParameterIndex != -1)

        generateParameterLoad(operationParameterIndex)
        ldc(operationParameterIndex)
        anewarray<Any>()

        (0 until operationParameterIndex).forEach {
            dup
            ldc(it)
            generateParameterLoad(it)
            aastore
        }

        invokeinterface(Operation::class, "call", Any::class, Array<Any>::class)
    }
}
