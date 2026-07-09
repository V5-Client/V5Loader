package com.chattriggers.ctjs.internal.launch.generation

import codes.som.koffee.MethodAssembly
import codes.som.koffee.insns.jvm.aconst_null
import com.chattriggers.ctjs.internal.launch.Descriptor
import com.chattriggers.ctjs.internal.launch.Inject
import com.chattriggers.ctjs.internal.utils.descriptor
import org.objectweb.asm.tree.MethodNode
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.injection.Inject as SPInject

internal class InjectGenerator(
    ctx: GenerationContext,
    id: Int,
    private val inject: Inject,
) : InjectorGenerator(ctx, id) {
    override val type = "inject"

    override fun getInjectionSignature(): InjectionSignature {
        val (mappedMethod, method) = ctx.findMethod(inject.method)
        val parameters = mutableListOf<Parameter>()

        if (mappedMethod.returnType.value == "V") {
            parameters.add(Parameter(CallbackInfo::class.descriptor()))
        } else {
            parameters.add(Parameter(CallbackInfoReturnable::class.descriptor()))
        }

        parameters.addLocals(inject.locals)

        return InjectionSignature(
            mappedMethod,
            parameters,
            Descriptor.Primitive.VOID,
            method.isStatic,
        )
    }

    override fun attachAnnotation(node: MethodNode, signature: InjectionSignature) {
        node.visitAnnotation(SPInject::class.java.descriptorString(), true).apply {
            visitOptional("id", inject.id)
            visit("method", signature.targetMethod.toFullDescriptor())
            visitOptional("slice", inject.slice?.map(Utils::createSliceAnnotation))
            visitOptional("at", inject.at?.map(Utils::createAtAnnotation))
            visitOptional("cancellable", inject.cancellable)
            visitOptional("remap", inject.remap)
            visitOptional("require", inject.require)
            visitOptional("expect", inject.expect)
            visitOptional("allow", inject.allow)
            visitOptional("constraints", inject.constraints)
            visitEnd()
        }
    }

    context(MethodAssembly)
    override fun generateNotAttachedBehavior() {
        // This method is expected to leave something on the stack
        aconst_null
    }
}
