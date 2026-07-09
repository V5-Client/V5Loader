package com.chattriggers.ctjs.internal.launch.generation

import codes.som.koffee.MethodAssembly
import com.chattriggers.ctjs.internal.launch.At
import com.chattriggers.ctjs.internal.launch.ModifyReceiver
import com.chattriggers.ctjs.internal.utils.descriptorString
import org.objectweb.asm.tree.MethodNode
import com.llamalad7.mixinextras.injector.ModifyReceiver as SPModifyReceiver

internal class ModifyReceiverGenerator(
    ctx: GenerationContext,
    id: Int,
    private val modifyReceiver: ModifyReceiver,
) : InjectorGenerator(ctx, id) {
    override val type = "modifyReceiver"

    override fun getInjectionSignature(): InjectionSignature {
        val (mappedMethod, method) = ctx.findMethod(modifyReceiver.method)

        val (owner, extraParams) = when (val atTarget = modifyReceiver.at.atTarget) {
            is At.InvokeTarget -> atTarget.descriptor.owner to atTarget.descriptor.parameters
            is At.FieldTarget -> {
                check(atTarget.isStatic != null && atTarget.isGet != null) {
                    "ModifyReceiver targeting FIELD expects an opcode value"
                }
                check(!atTarget.isStatic) { "ModifyReceiver targeting FIELD expects a non-static field access" }
                if (atTarget.isGet) {
                    atTarget.descriptor.owner to emptyList()
                } else atTarget.descriptor.owner to listOf(atTarget.descriptor.type!!)
            }
            else -> error("Unsupported At.value for ModifyReceiver: ${atTarget.targetName}")
        }

        val params = listOf(Parameter(owner!!)) +
            extraParams!!.map(::Parameter) +
            modifyReceiver.locals?.map(Utils::getParameterFromLocal).orEmpty()

        return InjectionSignature(
            mappedMethod,
            params,
            owner,
            method.isStatic,
        )
    }

    override fun attachAnnotation(node: MethodNode, signature: InjectionSignature) {
        node.visitAnnotation(SPModifyReceiver::class.descriptorString(), true).apply {
            visit("method", listOf(signature.targetMethod.toFullDescriptor()))
            visit("at", Utils.createAtAnnotation(modifyReceiver.at))
            visitOptional("slice", modifyReceiver.slice?.map(Utils::createSliceAnnotation))
            visitOptional("remap", modifyReceiver.remap)
            visitOptional("require", modifyReceiver.require)
            visitOptional("expect", modifyReceiver.expect)
            visitOptional("allow", modifyReceiver.allow)
            visitEnd()
        }
    }

    context(MethodAssembly)
    override fun generateNotAttachedBehavior() {
        generateParameterLoad(0)
    }
}
