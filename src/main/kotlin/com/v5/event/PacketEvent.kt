package com.v5.event

import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.network.packet.Packet

object PacketEvent {

  fun interface ReceiveEvent {
    fun trigger(packet: Packet<*>)
  }

  @JvmField
  val RECEIVE = bake<ReceiveEvent> { v -> ReceiveEvent { ctx -> v.forEach { it.trigger(ctx) } } }

  private inline fun <reified T> bake(noinline v: (Array<T>) -> T): Event<T> =
    EventFactory.createArrayBacked(T::class.java, v)

}
