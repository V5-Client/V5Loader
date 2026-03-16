package com.chattriggers.ctjs.internal.listeners

import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.triggers.CancellableEvent
import com.chattriggers.ctjs.api.triggers.TriggerType
import com.chattriggers.ctjs.api.world.World
import com.chattriggers.ctjs.internal.engine.CTEvents
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.utils.Initializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import org.lwjgl.glfw.GLFW

internal object MouseListener : Initializer {
    private val mouseState = IntArray(5) { Int.MIN_VALUE }
    private val draggedState = arrayOfNulls<State>(5)
    private val extraMouseState = mutableMapOf<Int, Int>()

    private class State(val x: Double, val y: Double)

    override fun init() {
        CTEvents.RENDER_TICK.register {
            if (!JSLoader.hasTriggers(TriggerType.DRAGGED)) return@register
            if (!World.isLoaded())
                return@register

            for (button in 0..4) {
                val previousState = draggedState[button] ?: continue

                val x = Client.getMouseX()
                val y = Client.getMouseY()

                if (x == previousState.x && y == previousState.y)
                    continue

                CTEvents.MOUSE_DRAGGED.invoker().process(
                    x - previousState.x,
                    y - previousState.y,
                    x,
                    y,
                    button,
                )

                // update dragged
                draggedState[button] = State(x, y)
            }
        }

        CTEvents.MOUSE_CLICKED.register(TriggerType.CLICKED::triggerAll)
        CTEvents.MOUSE_SCROLLED.register(TriggerType.SCROLLED::triggerAll)
        CTEvents.MOUSE_DRAGGED.register(TriggerType.DRAGGED::triggerAll)
        CTEvents.GUI_MOUSE_DRAG.register(TriggerType.GUI_MOUSE_DRAG::triggerAll)

        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                if (!JSLoader.hasTriggers(TriggerType.GUI_MOUSE_CLICK)) return@register true
                val event = CancellableEvent()
                TriggerType.GUI_MOUSE_CLICK.triggerAll(click.x, click.y, click.button(), true, screen, event)

                !event.isCanceled()
            }

            ScreenMouseEvents.allowMouseRelease(screen).register { _, click ->
                if (!JSLoader.hasTriggers(TriggerType.GUI_MOUSE_CLICK)) return@register true
                val event = CancellableEvent()
                TriggerType.GUI_MOUSE_CLICK.triggerAll(click.x, click.y, click.button(), false, screen, event)

                !event.isCanceled()
            }
        }
    }

    @JvmStatic
    fun onRawMouseInput(button: Int, action: Int) {
        if (!World.isLoaded()) {
            for (buttonIndex in 0..4) {
                mouseState[buttonIndex] = Int.MIN_VALUE
                draggedState[buttonIndex] = null
            }
            extraMouseState.clear()
            return
        }

        if (button == -1)
            return

        if ((button in 0..4 && action == mouseState[button]) || (button !in 0..4 && action == extraMouseState[button]))
            return

        val x = Client.getMouseX()
        val y = Client.getMouseY()

        CTEvents.MOUSE_CLICKED.invoker().process(x, y, button, action == GLFW.GLFW_PRESS)

        if (button !in 0..4) {
            extraMouseState[button] = action
            return
        }

        mouseState[button] = action

        if (action == GLFW.GLFW_PRESS) {
            draggedState[button] = State(x, y)
        } else {
            draggedState[button] = null
        }
    }

    @JvmStatic
    fun onRawMouseScroll(dy: Double) {
        if (!JSLoader.hasTriggers(TriggerType.SCROLLED)) return
        CTEvents.MOUSE_SCROLLED.invoker().process(Client.getMouseX(), Client.getMouseY(), dy)
    }
}
