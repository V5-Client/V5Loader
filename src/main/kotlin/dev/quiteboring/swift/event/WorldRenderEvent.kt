package dev.quiteboring.swift.event

import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory

object WorldRenderEvent {

  fun interface StartEvent {
    fun trigger(ctx: Context)
  }

  fun interface LastEvent {
    fun trigger(ctx: Context)
  }

  @JvmField
  val START: Event<StartEvent> = EventFactory.createArrayBacked(StartEvent::class.java) { listeners ->
    StartEvent { ctx -> listeners.forEach { it.trigger(ctx) } }
  }

  @JvmField
  val LAST: Event<LastEvent> = EventFactory.createArrayBacked(LastEvent::class.java) { listeners ->
    LastEvent { ctx -> listeners.forEach { it.trigger(ctx) } }
  }
}
