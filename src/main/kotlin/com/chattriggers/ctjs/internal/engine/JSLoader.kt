package com.chattriggers.ctjs.internal.engine

import com.chattriggers.ctjs.api.triggers.ITriggerType
import com.chattriggers.ctjs.api.triggers.Trigger
import com.chattriggers.ctjs.api.Mappings
import com.chattriggers.ctjs.engine.LogType
import com.chattriggers.ctjs.engine.MixinCallback
import com.chattriggers.ctjs.engine.printToConsole
import com.chattriggers.ctjs.engine.printTraceToConsole
import com.chattriggers.ctjs.internal.engine.module.Module
import com.chattriggers.ctjs.internal.engine.module.ModuleManager.modulesFolder
import com.chattriggers.ctjs.internal.launch.IInjector
import com.chattriggers.ctjs.internal.launch.Mixin
import com.chattriggers.ctjs.internal.launch.MixinDetails
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.NativeJavaClass
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider
import org.mozilla.javascript.commonjs.module.Require
import org.mozilla.javascript.commonjs.module.provider.StrongCachingModuleScriptProvider
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider

@OptIn(ExperimentalContracts::class)
object JSLoader {
    private class TriggerBucket {
        val ordered = ArrayList<Trigger>()
        @Volatile var snapshot: Array<Trigger> = emptyArray()
        @Volatile var dirty: Boolean = false
    }

    private val triggers = ConcurrentHashMap<ITriggerType, TriggerBucket>()
    private val emptyArgs = emptyArray<Any?>()
    private val dispatchContext = ThreadLocal<Context?>()

    private lateinit var moduleScope: Scriptable
    private lateinit var evalScope: Scriptable
    private lateinit var require: CTRequire
    private lateinit var moduleProvider: ModuleScriptProvider

    private var mixinLibsLoaded = false
    private var mixinsFinalized = false
    private var nextMixinId = 0
    private val mixinIdMap = mutableMapOf<Int, MixinCallback>()
    private val mixins = mutableMapOf<Mixin, MixinDetails>()

    private val INVOKE_MIXIN_CALL =
            MethodHandles.lookup()
                    .findStatic(
                            JSLoader::class.java,
                            "invokeMixin",
                            MethodType.methodType(
                                    Any::class.java,
                                    Callable::class.java,
                                    Array<Any?>::class.java
                            ),
                    )

    fun setup(jars: List<URL>) {
        JSContextFactory.addAllURLs(jars)

        val cx = JSContextFactory.enterContext()
        moduleProvider = StrongCachingModuleScriptProvider(
            UrlModuleSourceProvider(listOf(modulesFolder.toURI()), listOf())
        )

        moduleScope = ImporterTopLevel(cx)
        (moduleScope as ScriptableObject).defineProperty("global", moduleScope, 2)

        evalScope = ImporterTopLevel(cx)
        (evalScope as ScriptableObject).defineProperty("global", evalScope, 2)

        require = CTRequire(moduleProvider)
        require.install(moduleScope)
        require.install(evalScope)
        Context.exit()

        mixinLibsLoaded = false
    }

    internal fun mixinSetup(modules: List<Module>): Map<Mixin, MixinDetails> {
        loadMixinLibs()

        wrapInContext {
            modules.mapNotNull { module ->
                module.metadata.mixinEntry?.let { module to it }
            }.forEach { (module, mixinEntry) ->
                try {
                    val uri = File(module.folder, mixinEntry).normalize().toURI()
                    require.loadCTModule(uri.toString(), uri)
                } catch (e: Throwable) {
                    e.printTraceToConsole()
                }
            }
        }

        mixinsFinalized = true

        return mixins
    }

    fun entrySetup(): Unit = wrapInContext {
        if (!mixinLibsLoaded) loadMixinLibs()
        installProvidedTypes(it, moduleScope)
        installProvidedTypes(it, evalScope)

        val moduleProvidedLibs =
                readResource("/assets/ctjs/js/moduleProvidedLibs.js")

        try {
            val script = it.compileString(moduleProvidedLibs, "moduleProvided", 1, null)
            script.exec(it, moduleScope)
            script.exec(it, evalScope)
        } catch (e: Throwable) {
            e.printTraceToConsole()
        }
    }

    fun entryPass(module: Module, entryURI: URI): Unit = wrapInContext {
        try {
            require.loadCTModule(module.name, entryURI)
        } catch (e: Throwable) {
            "Error loading module ${module.name}".printToConsole(LogType.ERROR)
            e.printTraceToConsole()
        }
    }

    fun exec(type: ITriggerType, args: Array<out Any?>) {
        execSnapshot(type, args)
    }

    fun exec(type: ITriggerType, arg0: Any?) {
        execSnapshot(type, arrayOf(arg0))
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?) {
        execSnapshot(type, arrayOf(arg0, arg1))
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?, arg2: Any?) {
        execSnapshot(type, arrayOf(arg0, arg1, arg2))
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?) {
        execSnapshot(type, arrayOf(arg0, arg1, arg2, arg3))
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?) {
        execSnapshot(type, arrayOf(arg0, arg1, arg2, arg3, arg4))
    }

    fun execNoArgs(type: ITriggerType) {
        execSnapshot(type, emptyArgs)
    }

    fun hasTriggers(type: ITriggerType): Boolean {
        return triggers.containsKey(type)
    }

    fun addTrigger(trigger: Trigger) {
        val bucket = triggers.getOrPut(trigger.type) { TriggerBucket() }
        synchronized(bucket) {
            val existing = Collections.binarySearch(bucket.ordered, trigger)
            if (existing >= 0) return
            val insertAt = -existing - 1
            bucket.ordered.add(insertAt, trigger)
            bucket.dirty = true
        }
    }

    fun clearTriggers() {
        triggers.clear()
    }

    fun removeTrigger(trigger: Trigger) {
        val bucket = triggers[trigger.type] ?: return
        synchronized(bucket) {
            val index = Collections.binarySearch(bucket.ordered, trigger)
            if (index < 0) return
            bucket.ordered.removeAt(index)

            if (bucket.ordered.isEmpty()) {
                bucket.snapshot = emptyArray()
                bucket.dirty = false
                triggers.remove(trigger.type, bucket)
            } else {
                bucket.dirty = true
            }
        }
    }

    internal inline fun <T> wrapInContext(
            context: Context? = null,
            crossinline block: (Context) -> T
    ): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

        var cx = context ?: Context.getCurrentContext()
        val missingContext = cx == null
        if (missingContext) cx = JSContextFactory.enterContext()

        try {
            return block(cx)
        } finally {
            if (missingContext) Context.exit()
        }
    }

    fun eval(code: String): String? {
        return wrapInContext {
            ScriptRuntime.doTopCall(
                    { cx, scope, thisObj, args ->
                        try {
                            ScriptRuntime.toString(
                                    cx.evaluateString(scope, code, "<eval>", 1, null)
                            )
                        } catch (e: Throwable) {
                            e.printTraceToConsole()
                        }
                    },
                    it,
                    evalScope,
                    evalScope,
                    emptyArray(),
                    true,
            ) as?
                    String
        }
    }

    fun invoke(method: Callable, args: Array<out Any?>, thisObj: Scriptable = moduleScope): Any? {
        return withDispatchContext { invokeInContext(it, method, args, thisObj) }
    }

    fun invokeVoid(method: Callable, args: Array<out Any?>, thisObj: Scriptable = moduleScope) {
        withDispatchContext { invokeVoidInContext(it, method, args, thisObj) }
    }

    fun trigger(trigger: Trigger, method: Any, args: Array<out Any?>) {
        withCallableTrigger(trigger, method) { callable ->
            invoke(callable, args)
        }
    }

    fun triggerVoid(trigger: Trigger, method: Any, args: Array<out Any?>) {
        withCallableTrigger(trigger, method) { callable ->
            invokeVoid(callable, args)
        }
    }

    private inline fun withCallableTrigger(trigger: Trigger, method: Any, block: (Callable) -> Unit) {
        try {
            require(method is Callable) {
                "Need to pass actual function to the register function, not the name!"
            }
            block(method)
        } catch (e: Throwable) {
            e.printTraceToConsole()
            removeTrigger(trigger)
        }
    }

    private fun execSnapshot(type: ITriggerType, args: Array<out Any?>) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        wrapInContext {
            val previous = dispatchContext.get()
            dispatchContext.set(it)
            try {
                var i = 0
                while (i < snapshot.size) {
                    snapshot[i].trigger(args)
                    i++
                }
            } finally {
                if (previous == null) dispatchContext.remove()
                else dispatchContext.set(previous)
            }
        }
    }

    private inline fun <T> withDispatchContext(crossinline block: (Context) -> T): T {
        val cx = dispatchContext.get()
        return if (cx != null) block(cx) else wrapInContext(block = block)
    }

    private fun getSnapshot(type: ITriggerType): Array<Trigger>? {
        val bucket = triggers[type] ?: return null
        if (!bucket.dirty) return bucket.snapshot

        synchronized(bucket) {
            if (bucket.dirty) {
                bucket.snapshot = bucket.ordered.toTypedArray()
                bucket.dirty = false
            }
            return bucket.snapshot
        }
    }

    private fun invokeInContext(
            context: Context,
            method: Callable,
            args: Array<out Any?>,
            thisObj: Scriptable = moduleScope
    ): Any? {
        return Context.jsToJava(method.call(context, moduleScope, thisObj, args), Any::class.java)
    }

    private fun invokeVoidInContext(
            context: Context,
            method: Callable,
            args: Array<out Any?>,
            thisObj: Scriptable = moduleScope
    ) {
        method.call(context, moduleScope, thisObj, args)
    }

    private fun loadMixinLibs() {
        if (mixinLibsLoaded) return

        val mixinProvidedLibs =
                readResource("/assets/ctjs/js/mixinProvidedLibs.js")

        wrapInContext {
            try {
                it.evaluateString(moduleScope, mixinProvidedLibs, "mixinProvided", 1, null)
            } catch (e: Throwable) {
                e.printTraceToConsole()
            }
        }

        mixinLibsLoaded = true
    }

    @JvmStatic fun mixinIsAttached(id: Int) = mixinIdMap[id]?.method != null

    fun invokeMixinLookup(id: Int): MixinCallback {
        val callback =
                mixinIdMap[id] ?: error("Unknown mixin id $id for loader ${this::class.simpleName}")

        callback.handle =
                if (callback.method != null) {
                    try {
                        require(callback.method is Callable) {
                            "The value passed to MixinCallback.attach() must be a function"
                        }

                        INVOKE_MIXIN_CALL.bindTo(callback.method)
                    } catch (e: Throwable) {
                        "Error loading mixin callback".printToConsole()
                        e.printTraceToConsole()

                        MethodHandles.dropArguments(
                                MethodHandles.constant(Any::class.java, null),
                                0,
                                Array<Any?>::class.java,
                        )
                    }
                } else null

        return callback
    }

    @JvmStatic
    fun invokeMixin(func: Callable, args: Array<Any?>): Any? {
        return wrapInContext {
            Context.jsToJava(func.call(it, moduleScope, moduleScope, args), Any::class.java)
        }
    }

    fun registerInjector(mixin: Mixin, injector: IInjector): MixinCallback? {
        return if (mixinsFinalized) {
            val existing = mixins[mixin]?.injectors?.find { it.injector == injector }
            if (existing != null) {
                existing
            } else {
                ("A new injector mixin was registered at runtime. This will require a restart, and will " +
                                "have no effect until then!")
                        .printToConsole()
                null
            }
        } else {
            val id = nextMixinId++
            MixinCallback(id, injector).also {
                mixinIdMap[id] = it
                mixins.getOrPut(mixin, ::MixinDetails).injectors.add(it)
            }
        }
    }

    fun registerFieldWidener(mixin: Mixin, fieldName: String, isMutable: Boolean) {
        if (!mixinsFinalized)
                mixins.getOrPut(mixin, ::MixinDetails).fieldWideners[fieldName] = isMutable
    }

    fun registerMethodWidener(mixin: Mixin, methodName: String, isMutable: Boolean) {
        if (!mixinsFinalized)
                mixins.getOrPut(mixin, ::MixinDetails).methodWideners[methodName] = isMutable
    }

    private fun readResource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)) {
        "The embedded resource '$name' cannot be found."
    }.bufferedReader().use { it.readText() }

    private fun installProvidedTypes(cx: Context, scope: Scriptable) {
        readResource("/provided-types.properties").lineSequence()
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .forEach { line ->
                    val name = line.substringBefore('=')
                    val path = line.substringAfter('=')
                    val mappedPath = Mappings.mapClassName(path)?.replace('/', '.') ?: path
                    val clazz = cx.applicationClassLoader.loadClass(mappedPath)
                    ScriptableObject.putProperty(scope, name, NativeJavaClass(scope, clazz))
                }
    }

    private class CTRequire(
            moduleProvider: ModuleScriptProvider,
    ) : Require(Context.getContext(), moduleScope, moduleProvider, null, null, false) {
        fun loadCTModule(cachedName: String, uri: URI): Scriptable {
            return getExportedModuleInterface(Context.getContext(), cachedName, uri, null, false)
        }
    }
}
