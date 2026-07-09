package com.chattriggers.ctjs.internal.launch.generation

import codes.som.koffee.assembleClass
import codes.som.koffee.modifiers.public
import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.internal.launch.Inject
import com.chattriggers.ctjs.internal.launch.MixinDetails
import com.chattriggers.ctjs.internal.launch.ModifyArg
import com.chattriggers.ctjs.internal.launch.ModifyArgs
import com.chattriggers.ctjs.internal.launch.ModifyConstant
import com.chattriggers.ctjs.internal.launch.ModifyExpressionValue
import com.chattriggers.ctjs.internal.launch.ModifyReceiver
import com.chattriggers.ctjs.internal.launch.ModifyReturnValue
import com.chattriggers.ctjs.internal.launch.ModifyVariable
import com.chattriggers.ctjs.internal.launch.Redirect
import com.chattriggers.ctjs.internal.launch.WrapOperation
import com.chattriggers.ctjs.internal.launch.WrapWithCondition
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import org.spongepowered.asm.mixin.Mixin as SPMixin

internal class DynamicMixinGenerator(private val ctx: GenerationContext, private val details: MixinDetails) {
    fun generate(): ByteArray {
        val mixinClassNode = assembleClass(public, ctx.generatedClassFullPath, version = Opcodes.V25) {
            for ((id, injector) in details.injectors) {
                when (injector) {
                    is Inject -> InjectGenerator(ctx, id, injector).generate()
                    is Redirect -> RedirectGenerator(ctx, id, injector).generate()
                    is ModifyArg -> ModifyArgGenerator(ctx, id, injector).generate()
                    is ModifyArgs -> ModifyArgsGenerator(ctx, id, injector).generate()
                    is ModifyConstant -> ModifyConstantGenerator(ctx, id, injector).generate()
                    is ModifyExpressionValue -> ModifyExpressionValueGenerator(ctx, id, injector).generate()
                    is ModifyReceiver -> ModifyReceiverGenerator(ctx, id, injector).generate()
                    is ModifyReturnValue -> ModifyReturnValueInjector(ctx, id, injector).generate()
                    is ModifyVariable -> ModifyVariableGenerator(ctx, id, injector).generate()
                    is WrapOperation -> WrapOperationGenerator(ctx, id, injector).generate()
                    is WrapWithCondition -> WrapWithConditionGenerator(ctx, id, injector).generate()
                }
            }
        }

        val mixinAnnotation = mixinClassNode.visitAnnotation(SPMixin::class.java.descriptorString(), false)
        val mixin = ctx.mixin
        mixinAnnotation.visit("targets", listOf(ctx.mappedClass.name.value))
        mixinAnnotation.visitOptional("priority", mixin.priority)
        mixinAnnotation.visitOptional("remap", mixin.remap)
        mixinAnnotation.visitEnd()

        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        mixinClassNode.accept(writer)
        val bytes = writer.toByteArray()

        if (CTJS.isDevelopment) {
            val dir = File(CTJS.configLocation, "ChatTriggers/mixin-classes")
            dir.mkdirs()
            File(dir, "${ctx.generatedClassName}.class").writeBytes(bytes)
        }

        return bytes
    }
}
