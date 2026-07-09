package com.chattriggers.ctjs.internal.launch.generation

import codes.som.koffee.MethodAssembly
import codes.som.koffee.insns.jvm.aconst_null
import com.chattriggers.ctjs.internal.launch.Descriptor
import com.chattriggers.ctjs.internal.launch.ModifyArgs
import com.chattriggers.ctjs.internal.utils.descriptor
import com.chattriggers.ctjs.internal.utils.descriptorString
import org.objectweb.asm.tree.MethodNode
import org.spongepowered.asm.mixin.injection.invoke.arg.Args
import org.spongepowered.asm.mixin.injection.ModifyArgs as SPModifyArgs

internal class ModifyArgsGenerator(
    ctx: GenerationContext,
    id: Int,
    private val modifyArgs: ModifyArgs,
) : InjectorGenerator(ctx, id) {
    override val type = "modifyArgs"

    override fun getInjectionSignature(): InjectionSignature {
        val (mappedMethod, method) = ctx.findMethod(modifyArgs.method)

        val parameters = mutableListOf<Parameter>()
        parameters.add(Parameter(Args::class.descriptor()))
        parameters.addLocals(modifyArgs.locals)

        return InjectionSignature(
            mappedMethod,
            parameters,
            Descriptor.Primitive.VOID,
            method.isStatic,
        )
    }

    override fun attachAnnotation(node: MethodNode, signature: InjectionSignature) {
        node.visitAnnotation(SPModifyArgs::class.descriptorString(), true).apply {
            visit("method", listOf(signature.targetMethod.toFullDescriptor()))
            visitOptional("slice", modifyArgs.slice)
            visit("at", Utils.createAtAnnotation(modifyArgs.at))
            visitOptional("remap", modifyArgs.remap)
            visitOptional("require", modifyArgs.require)
            visitOptional("expect", modifyArgs.expect)
            visitOptional("allow", modifyArgs.allow)
            visitOptional("constraints", modifyArgs.constraints)
        }
    }

    context(MethodAssembly)
    override fun generateNotAttachedBehavior() {
        // This method is expected to leave something on the stack
        aconst_null
    }
}
