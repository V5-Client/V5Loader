package com.chattriggers.ctjs.api.triggers

import com.chattriggers.ctjs.api.entity.BlockEntity
import com.chattriggers.ctjs.api.entity.Entity
import net.minecraft.network.protocol.Packet

sealed class ClassFilterTrigger<Wrapped, Unwrapped>(
    method: Any,
    triggerType: ITriggerType,
    private val wrappedClass: Class<Wrapped>,
) : Trigger(method, triggerType) {
    @Volatile
    private var triggerClasses: Array<Class<Unwrapped>> = emptyArray()

    /**
     * Alias for `setFilteredClasses([A.class])`
     *
     * @param clazz The class for which this trigger should run for
     */
    fun setFilteredClass(clazz: Class<Unwrapped>) = apply {
        triggerClasses = arrayOf(clazz)
    }

    /**
     * Sets which classes this trigger should run for. If the list is empty, it runs
     * for every class.
     *
     * @param classes The classes for which this trigger should run for
     * @return This trigger object for chaining
     */
    fun setFilteredClasses(classes: List<Class<Unwrapped>>) = apply {
        triggerClasses = classes.toTypedArray()
    }

    override fun trigger(args: Array<out Any?>) {
        val arg = args.getOrNull(0) ?: error("First argument of $type trigger can not be null")

        check(wrappedClass.isInstance(arg)) {
            "Expected first argument of $type trigger to be instance of $wrappedClass"
        }

        val classes = triggerClasses
        if (classes.isEmpty()) {
            callMethod(args)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val unwrapped = unwrap(arg as Wrapped)

        if (classes.any { it.isInstance(unwrapped) }) {
            callMethod(args)
        }
    }

    protected abstract fun unwrap(wrapped: Wrapped): Unwrapped
}

class RenderEntityTrigger(method: Any) : ClassFilterTrigger<Entity, net.minecraft.world.entity.Entity>(
    method,
    TriggerType.RENDER_ENTITY,
    Entity::class.java,
) {
    override fun unwrap(wrapped: Entity): net.minecraft.world.entity.Entity = wrapped.toMC()
}

class RenderBlockEntityTrigger(method: Any) : ClassFilterTrigger<BlockEntity, net.minecraft.world.level.block.entity.BlockEntity>(
    method,
    TriggerType.RENDER_BLOCK_ENTITY,
    BlockEntity::class.java
) {
    override fun unwrap(wrapped: BlockEntity): net.minecraft.world.level.block.entity.BlockEntity = wrapped.toMC()
}

class PacketTrigger(method: Any, triggerType: ITriggerType) : ClassFilterTrigger<Packet<*>, Packet<*>>(
    method,
    triggerType,
    Packet::class.java,
) {
    override fun unwrap(wrapped: Packet<*>): Packet<*> = wrapped
}
