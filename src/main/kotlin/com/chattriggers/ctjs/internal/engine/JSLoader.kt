package com.chattriggers.ctjs.internal.engine

import com.chattriggers.ctjs.api.triggers.ITriggerType
import com.chattriggers.ctjs.api.triggers.Trigger
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
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.mozilla.javascript.*
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
    private val hasTriggerCache = ConcurrentHashMap<ITriggerType, Boolean>()
    private val emptyArgs = emptyArray<Any?>()
    private val dispatchContext = ThreadLocal<Context?>()
    private val oneArgPool = ThreadLocal.withInitial { ArrayDeque<Array<Any?>>() }
    private val twoArgPool = ThreadLocal.withInitial { ArrayDeque<Array<Any?>>() }
    private val threeArgPool = ThreadLocal.withInitial { ArrayDeque<Array<Any?>>() }
    private val fourArgPool = ThreadLocal.withInitial { ArrayDeque<Array<Any?>>() }
    private val fiveArgPool = ThreadLocal.withInitial { ArrayDeque<Array<Any?>>() }

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
            modules.forEach { module ->
                try {
                    val uri = File(module.folder, module.metadata.mixinEntry!!).normalize().toURI()
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

        val moduleProvidedLibs =
                saveResource(
                        "/assets/ctjs/js/moduleProvidedLibs.js",
                        File(modulesFolder.parentFile, "chattriggers-modules-provided-libs.js"),
                )

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
            println("Error loading module ${module.name}")
            "Error loading module ${module.name}".printToConsole(LogType.ERROR)
            e.printTraceToConsole()
        }
    }

    fun exec(type: ITriggerType, args: Array<out Any?>) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        execSnapshot(snapshot, args)
    }

    fun exec(type: ITriggerType, arg0: Any?) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        withArgs(oneArgPool, 1) { args ->
            args[0] = arg0
            execSnapshot(snapshot, args)
        }
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        withArgs(twoArgPool, 2) { args ->
            args[0] = arg0
            args[1] = arg1
            execSnapshot(snapshot, args)
        }
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?, arg2: Any?) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        withArgs(threeArgPool, 3) { args ->
            args[0] = arg0
            args[1] = arg1
            args[2] = arg2
            execSnapshot(snapshot, args)
        }
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        withArgs(fourArgPool, 4) { args ->
            args[0] = arg0
            args[1] = arg1
            args[2] = arg2
            args[3] = arg3
            execSnapshot(snapshot, args)
        }
    }

    fun exec(type: ITriggerType, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        withArgs(fiveArgPool, 5) { args ->
            args[0] = arg0
            args[1] = arg1
            args[2] = arg2
            args[3] = arg3
            args[4] = arg4
            execSnapshot(snapshot, args)
        }
    }

    fun execNoArgs(type: ITriggerType) {
        val snapshot = getSnapshot(type) ?: return
        if (snapshot.isEmpty()) return

        execSnapshot(snapshot, emptyArgs)
    }

    fun hasTriggers(type: ITriggerType): Boolean {
        return hasTriggerCache[type] == true
    }

    fun addTrigger(trigger: Trigger) {
        val bucket = triggers.getOrPut(trigger.type) { TriggerBucket() }
        synchronized(bucket) {
            val existing = Collections.binarySearch(bucket.ordered, trigger)
            if (existing >= 0) return
            val insertAt = -existing - 1
            bucket.ordered.add(insertAt, trigger)
            bucket.dirty = true
            hasTriggerCache[trigger.type] = true
        }
    }

    fun clearTriggers() {
        triggers.clear()
        hasTriggerCache.clear()
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
                hasTriggerCache.remove(trigger.type)
            } else {
                bucket.dirty = true
                hasTriggerCache[trigger.type] = true
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
        val cx = dispatchContext.get()
        if (cx != null) return invokeInContext(cx, method, args, thisObj)

        return wrapInContext { invokeInContext(it, method, args, thisObj) }
    }

    fun invokeVoid(method: Callable, args: Array<out Any?>, thisObj: Scriptable = moduleScope) {
        val cx = dispatchContext.get()
        if (cx != null) {
            invokeVoidInContext(cx, method, args, thisObj)
            return
        }

        wrapInContext { invokeVoidInContext(it, method, args, thisObj) }
    }

    fun trigger(trigger: Trigger, method: Any, args: Array<out Any?>) {
        try {
            require(method is Callable) {
                "Need to pass actual function to the register function, not the name!"
            }
            invoke(method, args)
        } catch (e: Throwable) {
            e.printTraceToConsole()
            removeTrigger(trigger)
        }
    }

    fun triggerVoid(trigger: Trigger, method: Any, args: Array<out Any?>) {
        try {
            require(method is Callable) {
                "Need to pass actual function to the register function, not the name!"
            }
            invokeVoid(method, args)
        } catch (e: Throwable) {
            e.printTraceToConsole()
            removeTrigger(trigger)
        }
    }

    private fun execSnapshot(snapshot: Array<Trigger>, args: Array<out Any?>) {
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

    private fun getSnapshot(type: ITriggerType): Array<Trigger>? {
        val bucket = triggers[type] ?: return null
        if (!bucket.dirty) return bucket.snapshot

        synchronized(bucket) {
            if (bucket.dirty) {
                bucket.snapshot = bucket.ordered.toTypedArray()
                bucket.dirty = false
                if (bucket.snapshot.isEmpty()) hasTriggerCache.remove(type)
                else hasTriggerCache[type] = true
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

    private inline fun withArgs(
            poolRef: ThreadLocal<ArrayDeque<Array<Any?>>>,
            size: Int,
            block: (Array<Any?>) -> Unit
    ) {
        val pool = poolRef.get()
        val args = if (pool.isEmpty()) arrayOfNulls<Any?>(size) else pool.removeLast()
        try {
            block(args)
        } finally {
            var i = 0
            while (i < size) {
                args[i] = null
                i++
            }
            pool.addLast(args)
        }
    }

    private fun loadMixinLibs() {
        if (mixinLibsLoaded) return

        val mixinProvidedLibs =
                saveResource(
                        "/assets/ctjs/js/mixinProvidedLibs.js",
                        File(modulesFolder.parentFile, "chattriggers-mixin-provided-libs.js"),
                )

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

    private fun saveResource(resourceName: String?, outputFile: File): String {
        require(resourceName != null && resourceName != "") {
            "ResourcePath cannot be null or empty"
        }

        val parsedResourceName = resourceName.replace('\\', '/')
        val resource =
                javaClass.getResourceAsStream(parsedResourceName)
                        ?: throw IllegalArgumentException(
                                "The embedded resource '$parsedResourceName' cannot be found."
                        )

        val res = resource.bufferedReader().readText()
        org.apache.commons.io.FileUtils.write(outputFile, res, Charset.defaultCharset())
        return res
    }

    private class CTRequire(
            moduleProvider: ModuleScriptProvider,
    ) : Require(Context.getContext(), moduleScope, moduleProvider, null, null, false) {
        fun loadCTModule(cachedName: String, uri: URI): Scriptable {
            return getExportedModuleInterface(Context.getContext(), cachedName, uri, null, false)
        }
    }
}
