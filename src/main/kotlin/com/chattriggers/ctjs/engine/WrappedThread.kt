package com.chattriggers.ctjs.engine

import com.chattriggers.ctjs.internal.engine.JSContextFactory
import org.mozilla.javascript.Context
import java.util.concurrent.ForkJoinPool

@Suppress("unused")
class WrappedThread(private val task: Runnable) {
    fun start() {
        ForkJoinPool.commonPool().execute {
            try {
                JSContextFactory.enterContext()
                try {
                    task.run()
                } finally {
                    Context.exit()
                }
            } catch (e: Throwable) {
                e.printTraceToConsole()
            }
        }
    }

    // Provide the following methods as no-ops to avoid breaking
    // changes, as this class use to extend Thread
    fun run() {}
    fun stop() {}
    fun interrupt() {}
    fun isInterrupted() = false
    fun destroy() {}
    fun isAlive() = true
    fun suspend() {}
    fun resume() {}
    fun setDaemon(on: Boolean) {}
    fun getId() = 0L

    companion object {
        @JvmStatic
        @JvmOverloads
        fun sleep(millis: Long, nanos: Int = 0) = Thread.sleep(millis, nanos)

        @JvmStatic
        fun currentThread(): Thread = Thread.currentThread()
    }
}
