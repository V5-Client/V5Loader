package com.chattriggers.ctjs.api.triggers

import com.chattriggers.ctjs.api.entity.BlockEntity
import com.chattriggers.ctjs.api.entity.Entity
import net.minecraft.network.packet.Packet

sealed class ClassFilterTrigger<Wrapped, Unwrapped>(
    method: Any,
    private val triggerType: ITriggerType,
    private val wrappedClass: Class<Wrapped>?,
) : Trigger(method, triggerType) {
    @Volatile
    private var triggerClasses: Array<Class<Unwrapped>> = emptyArray()

    /**
     * Alias for `setFilteredClasses([A.class])`
     *
     * @param clazz The class for which this trigger should run for
     */
    fun setFilteredClass(clazz: Class<Unwrapped>) = setFilteredClasses(listOf(clazz))

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
        val currentWrappedClass = wrappedClass ?: return
        val arg = args.getOrNull(0) ?: error("First argument of $triggerType trigger can not be null")

        check(currentWrappedClass.isInstance(arg)) {
            "Expected first argument of $triggerType trigger to be instance of $currentWrappedClass"
        }

        val classes = triggerClasses
        if (classes.isEmpty()) {
            callMethod(args)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val placeholder = unwrap(arg as Wrapped)

        var i = 0
        while (i < classes.size) {
            if (classes[i].isInstance(placeholder)) {
                callMethod(args)
                return
            }
            i++
        }
    }

    protected abstract fun unwrap(wrapped: Wrapped): Unwrapped
}

class RenderEntityTrigger(method: Any) : ClassFilterTrigger<Entity, net.minecraft.entity.Entity>(
    method,
    TriggerType.RENDER_ENTITY,
    Entity::class.java,
) {
    override fun unwrap(wrapped: Entity): net.minecraft.entity.Entity = wrapped.toMC()
}

class RenderBlockEntityTrigger(method: Any) : ClassFilterTrigger<BlockEntity, net.minecraft.block.entity.BlockEntity>(
    method,
    TriggerType.RENDER_BLOCK_ENTITY,
    BlockEntity::class.java
) {
    override fun unwrap(wrapped: BlockEntity): net.minecraft.block.entity.BlockEntity = wrapped.toMC()
}

class PacketTrigger(method: Any, triggerType: ITriggerType) : ClassFilterTrigger<Packet<*>, Packet<*>>(
    method,
    triggerType,
    Packet::class.java,
) {
    override fun unwrap(wrapped: Packet<*>): Packet<*> = wrapped
}
