package com.chattriggers.ctjs.api.triggers

import com.chattriggers.ctjs.api.client.Client

class StepTrigger(method: Any) : Trigger(method, TriggerType.STEP) {
    private var fps: Long = 60L
    private var delay: Long = -1
    private var fpsIntervalMs: Long = 1000L / fps
    private var delayIntervalMs: Long = -1L
    private var systemTime: Long = Client.getSystemTime()
    private var elapsed: Long = 0L

    /**
     * Sets the frames per second that the trigger activates.
     * This has a maximum one step per second.
     * @param fps the frames per second to set
     * @return the trigger for method chaining
     */
    fun setFps(fps: Long) = apply {
        this.fps = if (fps < 1) 1L else fps
        fpsIntervalMs = 1000L / this.fps
        systemTime = Client.getSystemTime() + fpsIntervalMs
    }

    /**
     * Sets the delay in seconds between the trigger activation.
     * This has a minimum of one step every second. This will override [setFps].
     * @param delay The delay in seconds
     * @return the trigger for method chaining
     */
    fun setDelay(delay: Long) = apply {
        this.delay = if (delay < 1) 1L else delay
        delayIntervalMs = this.delay * 1000L
        systemTime = Client.getSystemTime() - delayIntervalMs
    }

    override fun register(): Trigger {
        systemTime = Client.getSystemTime()
        return super.register()
    }

    override fun trigger(args: Array<out Any?>) {
        val now = Client.getSystemTime()

        if (delay < 0) {
            // run trigger based on set fps value (60 per second by default)
            val interval = fpsIntervalMs
            val targetTime = now + interval

            while (systemTime < targetTime) {
                callMethod(arrayOf(++elapsed))
                systemTime += interval
            }
        } else {
            // run trigger based on set delay in seconds
            val interval = delayIntervalMs

            while (now > systemTime + interval) {
                callMethod(arrayOf(++elapsed))
                systemTime += interval
            }
        }
    }
}
