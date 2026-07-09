package com.chattriggers.ctjs.internal.launch

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.api.Mappings
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.launch.generation.DynamicMixinGenerator
import com.chattriggers.ctjs.internal.launch.generation.GenerationContext
import com.chattriggers.ctjs.internal.launch.generation.Utils
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.spongepowered.asm.mixin.Mixins
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

internal object DynamicMixinManager {
    internal const val GENERATED_PROTOCOL = "ct-generated"
    internal const val GENERATED_MIXIN = "ct-generated.mixins.json"
    internal const val GENERATED_PACKAGE = "com/chattriggers/ctjs/generated_mixins"

    lateinit var mixins: Map<Mixin, MixinDetails>
    @Volatile private var prepared = false

    fun initialize() {
        mixins = JSLoader.mixinSetup(ModuleManager.cachedModules.filter { it.metadata.mixinEntry != null })
    }

    fun applyAccessWideners() {
        for ((mixin, details) in mixins) {
            val mappedClass = Mappings.getMappedClass(mixin.target) ?: run {
                if (mixin.remap == false) {
                    Mappings.getUnmappedClass(mixin.target)
                } else {
                    error("Unknown class name ${mixin.target}")
                }
            }

            for ((field, isMutable) in details.fieldWideners)
                Utils.widenField(mappedClass, field, isMutable)
            for ((method, isMutable) in details.methodWideners)
                Utils.widenMethod(mappedClass, method, isMutable)
        }
    }

    @Synchronized
    fun prepare() {
        if (prepared) return

        Mappings.initialize()
        SecureLoader.onMixinPlugin()
        ModuleManager.setup()
        initialize()
        applyAccessWideners()

        prepared = true
    }

    fun applyMixins() {
        prepare()

        if (CTJS.isDevelopment) deleteOldMixinClasses()

        val dynamicMixins = mixins.map { (mixin, details) ->
            val ctx = GenerationContext(mixin)
            val generator = DynamicMixinGenerator(ctx, details)
            ByteBasedStreamHandler[ctx.generatedClassFullPath + ".class"] = generator.generate()
            ctx.generatedClassName
        }

        ByteBasedStreamHandler[GENERATED_MIXIN] = createDynamicMixinsJson(dynamicMixins)

        injectConfiguration()
    }

    private fun createDynamicMixinsJson(mixins: List<String>): ByteArray {
        return buildJsonObject {
            put("required", JsonPrimitive(true))
            put("minVersion", JsonPrimitive("0.8"))
            put("package", JsonPrimitive(GENERATED_PACKAGE.replace('/', '.')))
            put("compatibilityLevel", JsonPrimitive("JAVA_25"))
            putJsonObject("injectors") {
                put("defaultRequire", JsonPrimitive(1))
            }

            putJsonArray("client") { mixins.forEach(::add) }
        }.toString().toByteArray()
    }

    private fun injectConfiguration() {
        // Credit to hugeblank and his allium project for this setup
        // https://github.com/hugeblank/allium/blob/mixins/src/main/java/dev/hugeblank/allium/AlliumPreLaunch.java
        val classLoader = DynamicMixinManager::class.java.classLoader
        val addUrlMethod = classLoader::class.java.methods.first { it.name == "addUrlFwd" }
        addUrlMethod.isAccessible = true
        addUrlMethod.invoke(classLoader, ByteBasedStreamHandler.url)

        Mixins.addConfiguration(GENERATED_MIXIN)
    }

    private fun deleteOldMixinClasses() {
        val dir = File(CTJS.configLocation, "ChatTriggers/mixin-classes")
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private object ByteBasedStreamHandler : URLStreamHandler() {
        private val classBytes = mutableMapOf<String, ByteArray>()

        val url = URL.of(URI(GENERATED_PROTOCOL, null, "/", ""), ByteBasedStreamHandler)

        operator fun set(path: String, bytes: ByteArray) {
            check(classBytes.put(path, bytes) == null)
        }

        override fun openConnection(url: URL): URLConnection? =
            classBytes[url.path.drop(1)]?.let { Connection(url, it) }

        private class Connection(url: URL, private val bytes: ByteArray) : URLConnection(url) {
            override fun getInputStream() = ByteArrayInputStream(bytes)
            override fun connect() = throw UnsupportedOperationException()
        }
    }
}
