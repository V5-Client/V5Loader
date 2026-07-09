package com.chattriggers.ctjs.internal.launch.generation

import codes.som.koffee.MethodAssembly
import com.chattriggers.ctjs.internal.launch.Descriptor
import com.chattriggers.ctjs.internal.launch.ModifyReturnValue
import com.chattriggers.ctjs.internal.utils.descriptorString
import org.objectweb.asm.tree.MethodNode
import com.llamalad7.mixinextras.injector.ModifyReturnValue as SPModifyReturnValue

internal class ModifyReturnValueInjector(
    ctx: GenerationContext,
    id: Int,
    private val modifyReturnValue: ModifyReturnValue
) : InjectorGenerator(ctx, id) {
    override val type = "modifyReturnValue"

    override fun getInjectionSignature(): InjectionSignature {
        val (mappedMethod, method) = ctx.findMethod(modifyReturnValue.method)
        val returnType = Descriptor.Parser(mappedMethod.returnType.value).parseType(full = true)
        check(returnType != Descriptor.Primitive.VOID) {
            "ModifyReturnValue mixin cannot target a void method"
        }

        val parameters = mutableListOf(Parameter(returnType))
        parameters.addLocals(modifyReturnValue.locals)

        return InjectionSignature(
            mappedMethod,
            parameters,
            returnType,
            method.isStatic,
        )
    }

    override fun attachAnnotation(node: MethodNode, signature: InjectionSignature) {
        node.visitAnnotation(SPModifyReturnValue::class.descriptorString(), true).apply {
            visit("method", listOf(signature.targetMethod.toFullDescriptor()))
            visit("at", Utils.createAtAnnotation(modifyReturnValue.at))
            visitOptional("slice", modifyReturnValue.slice?.map(Utils::createSliceAnnotation))
            visitOptional("remap", modifyReturnValue.remap)
            visitOptional("require", modifyReturnValue.require)
            visitOptional("expect", modifyReturnValue.expect)
            visitOptional("allow", modifyReturnValue.allow)
            visitEnd()
        }
    }

    context(MethodAssembly)
    override fun generateNotAttachedBehavior() {
        generateParameterLoad(0)
    }
}
