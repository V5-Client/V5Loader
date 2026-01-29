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
import org.mozilla.javascript.*
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider
import org.mozilla.javascript.commonjs.module.Require
import org.mozilla.javascript.commonjs.module.provider.ModuleSource
import org.mozilla.javascript.commonjs.module.provider.ModuleSourceProvider
import org.mozilla.javascript.commonjs.module.provider.StrongCachingModuleScriptProvider
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider
import java.io.File
import java.io.StringReader
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
object JSLoader {
    private val triggers = ConcurrentHashMap<ITriggerType, ConcurrentSkipListSet<Trigger>>()

    private lateinit var moduleScope: Scriptable
    private lateinit var evalScope: Scriptable
    private lateinit var require: CTRequire
    private lateinit var moduleProvider: ModuleScriptProvider

    private var mixinLibsLoaded = false
    private var mixinsFinalized = false
    private var nextMixinId = 0
    private val mixinIdMap = mutableMapOf<Int, MixinCallback>()
    private val mixins = mutableMapOf<Mixin, MixinDetails>()

    private val virtualFiles = ConcurrentHashMap<String, String>()
    private val virtualFilesLowercase = ConcurrentHashMap<String, String>()

    private val INVOKE_MIXIN_CALL = MethodHandles.lookup().findStatic(
        JSLoader::class.java,
        "invokeMixin",
        MethodType.methodType(Any::class.java, Callable::class.java, Array<Any?>::class.java),
    )

    fun setup(jars: List<URL>) {
        JSContextFactory.addAllURLs(jars)

        val cx = JSContextFactory.enterContext()
        val diskProvider = UrlModuleSourceProvider(listOf(modulesFolder.toURI()), listOf())
        val virtualProvider = VirtualModuleSourceProvider(diskProvider)
        moduleProvider = StrongCachingModuleScriptProvider(virtualProvider)

        moduleScope = ImporterTopLevel(cx)
        evalScope = ImporterTopLevel(cx)
        require = CTRequire(moduleProvider)
        require.install(moduleScope)
        require.install(evalScope)
        Context.exit()

        mixinLibsLoaded = false
    }

    fun loadVirtualModule(entryPoint: String) {
        wrapInContext { cx ->
            try {
                val normalizedEntry = normalizePath(entryPoint)
                "Loading virtual entry point: $normalizedEntry".printToConsole()
                require.requireMain(cx, normalizedEntry)
            } catch (e: Throwable) {
                "Error loading virtual module $entryPoint".printToConsole(LogType.ERROR)
                e.printTraceToConsole()
            }
        }
    }

    fun normalizePath(path: String): String {
        if (path.isBlank()) return ""

        var result = path
            .replace('\\', '/')
            .trim()

        while (result.startsWith("./") || result.startsWith("/")) {
            result = result.removePrefix("./").removePrefix("/")
        }

        while (result.endsWith("/")) {
            result = result.dropLast(1)
        }

        val segments = result.split("/").toMutableList()
        var i = 0
        while (i < segments.size) {
            when {
                segments[i].isEmpty() -> {
                    segments.removeAt(i)
                }
                segments[i] == "." -> {
                    segments.removeAt(i)
                }
                segments[i] == ".." -> {
                    if (i > 0 && segments[i - 1] != "..") {
                        segments.removeAt(i)
                        segments.removeAt(i - 1)
                        i = maxOf(0, i - 1)
                    } else {
                        segments.removeAt(i)
                    }
                }
                else -> {
                    i++
                }
            }
        }

        return segments.joinToString("/")
    }

    fun addVirtualFile(path: String, content: String) {
        val normalizedPath = normalizePath(path)
        if (normalizedPath.isEmpty()) {
            "Warning: Attempted to add virtual file with empty path".printToConsole(LogType.WARN)
            return
        }

        virtualFiles[normalizedPath] = content
        virtualFilesLowercase[normalizedPath.lowercase()] = normalizedPath
    }

    fun getVirtualFile(path: String): String? {
        val normalizedPath = normalizePath(path)
        if (normalizedPath.isEmpty()) return null

        virtualFiles[normalizedPath]?.let { return it }

        val actualPath = virtualFilesLowercase[normalizedPath.lowercase()] ?: return null
        return virtualFiles[actualPath]
    }

    fun hasVirtualFile(path: String): Boolean {
        val normalizedPath = normalizePath(path)
        if (normalizedPath.isEmpty()) return false

        return virtualFiles.containsKey(normalizedPath) ||
                virtualFilesLowercase.containsKey(normalizedPath.lowercase())
    }

    fun getVirtualFilePaths(): Set<String> = virtualFiles.keys.toSet()

    fun clearVirtualFiles() {
        virtualFiles.clear()
        virtualFilesLowercase.clear()
    }

    fun getVirtualFileCount(): Int = virtualFiles.size

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
        mixinLibsLoaded = true

        return mixins
    }

    fun entrySetup(): Unit = wrapInContext {
        if (!mixinLibsLoaded)
            loadMixinLibs()

        val moduleProvidedLibs = saveResource(
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
        triggers[type]?.forEach { it.trigger(args) }
    }

    fun addTrigger(trigger: Trigger) {
        triggers.getOrPut(trigger.type, ::ConcurrentSkipListSet).add(trigger)
    }

    fun clearTriggers() {
        triggers.clear()
    }

    fun removeTrigger(trigger: Trigger) {
        triggers[trigger.type]?.remove(trigger)
    }

    internal inline fun <T> wrapInContext(context: Context? = null, crossinline block: (Context) -> T): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        var cx = context ?: Context.getCurrentContext()
        val missingContext = cx == null
        if (missingContext)
            cx = JSContextFactory.enterContext()

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
                        ScriptRuntime.toString(cx.evaluateString(scope, code, "<eval>", 1, null))
                    } catch (e: Throwable) {
                        e.printTraceToConsole()
                    }
                },
                it,
                evalScope,
                evalScope,
                emptyArray(),
                true,
            ) as? String
        }
    }

    fun invoke(method: Callable, args: Array<out Any?>, thisObj: Scriptable = moduleScope): Any? {
        return wrapInContext {
            Context.jsToJava(method.call(it, moduleScope, thisObj, args), Any::class.java)
        }
    }

    fun trigger(trigger: Trigger, method: Any, args: Array<out Any?>) {
        try {
            require(method is Callable) { "Need to pass actual function to the register function, not the name!" }
            invoke(method, args)
        } catch (e: Throwable) {
            e.printTraceToConsole()
            removeTrigger(trigger)
        }
    }

    private fun loadMixinLibs() {
        val mixinProvidedLibs = saveResource(
            "/assets/ctjs/js/mixinProvidedLibs.js",
            File(modulesFolder.parentFile, "chattriggers-mixin-provided-libs.js"),
        )

        wrapInContext {
            try {
                it.evaluateString(
                    moduleScope,
                    mixinProvidedLibs,
                    "mixinProvided",
                    1, null
                )
            } catch (e: Throwable) {
                e.printTraceToConsole()
            }
        }
    }

    @JvmStatic
    fun mixinIsAttached(id: Int) = mixinIdMap[id]?.method != null

    fun invokeMixinLookup(id: Int): MixinCallback {
        val callback = mixinIdMap[id] ?: error("Unknown mixin id $id for loader ${this::class.simpleName}")

        callback.handle = if (callback.method != null) {
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
                        "have no effect until then!").printToConsole()
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
        val resource = javaClass.getResourceAsStream(parsedResourceName)
            ?: throw IllegalArgumentException("The embedded resource '$parsedResourceName' cannot be found.")

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

    private class VirtualModuleSourceProvider(private val fallback: ModuleSourceProvider) : ModuleSourceProvider {

        companion object {
            private const val VIRTUAL_SCHEME = "file"
            private const val VIRTUAL_PREFIX = "/ct_virtual/"
        }

        private fun tryResolve(basePath: String): Pair<String, String>? {
            val normalized = normalizePath(basePath)
            if (normalized.isEmpty()) return null

            getVirtualFile(normalized)?.let {
                return normalized to it
            }

            if (!normalized.endsWith(".js") && !normalized.endsWith(".json")) {
                getVirtualFile("$normalized.js")?.let {
                    return "$normalized.js" to it
                }
            }

            getVirtualFile("$normalized/index.js")?.let {
                return "$normalized/index.js" to it
            }

            return null
        }

        private fun createModuleSource(resolvedPath: String, content: String, validator: Any?): ModuleSource {
            val uri = URI(VIRTUAL_SCHEME, null, "$VIRTUAL_PREFIX$resolvedPath", null)
            return ModuleSource(StringReader(content), null, uri, uri, validator)
        }

        override fun loadSource(moduleId: String?, paths: Scriptable?, validator: Any?): ModuleSource? {
            if (moduleId.isNullOrBlank()) return null

            val normalizedId = normalizePath(moduleId)
            if (normalizedId.isEmpty()) return fallback.loadSource(moduleId, paths, validator)

            tryResolve(normalizedId)?.let { (resolvedPath, content) ->
                return createModuleSource(resolvedPath, content, validator)
            }

            if (!normalizedId.startsWith("V5/", ignoreCase = true)) {
                tryResolve("V5/$normalizedId")?.let { (resolvedPath, content) ->
                    return createModuleSource(resolvedPath, content, validator)
                }
            }

            return fallback.loadSource(moduleId, paths, validator)
        }

        override fun loadSource(uri: URI?, baseUri: URI?, validator: Any?): ModuleSource? {
            if (uri == null) return fallback.loadSource(uri, baseUri, validator)

            val normalizedUri = try {
                uri.normalize()
            } catch (e: Exception) {
                uri
            }

            val path = normalizedUri.path ?: return fallback.loadSource(uri, baseUri, validator)

            if (normalizedUri.scheme == VIRTUAL_SCHEME && path.startsWith(VIRTUAL_PREFIX)) {
                val rawPath = path.removePrefix(VIRTUAL_PREFIX)
                val normalizedPath = normalizePath(rawPath)

                if (normalizedPath.isEmpty()) return null

                tryResolve(normalizedPath)?.let { (resolvedPath, content) ->
                    return createModuleSource(resolvedPath, content, validator)
                }

                "Virtual file not found: $normalizedPath".printToConsole(LogType.WARN)
                return null
            }

            return fallback.loadSource(uri, baseUri, validator)
        }
    }
}