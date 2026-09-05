package com.chattriggers.ctjs

import com.chattriggers.ctjs.api.Config
import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.client.DiscordRPC
import com.chattriggers.ctjs.api.client.screenCompat
import com.chattriggers.ctjs.api.client.KeyBind
import com.chattriggers.ctjs.api.client.Sound
import com.chattriggers.ctjs.api.client.WelcomeScreen
import com.chattriggers.ctjs.api.commands.DynamicCommands
import com.chattriggers.ctjs.api.message.ChatLib
import com.chattriggers.ctjs.api.render.Image
import com.chattriggers.ctjs.api.render.Render2D
import com.chattriggers.ctjs.api.render.skia.createSkijaPIP
import com.chattriggers.ctjs.api.triggers.TriggerType
import com.chattriggers.ctjs.api.world.Scoreboard
import com.chattriggers.ctjs.api.world.World
import com.chattriggers.ctjs.engine.Console
import com.chattriggers.ctjs.engine.Register
import com.chattriggers.ctjs.internal.commands.StaticCommand
import com.chattriggers.ctjs.internal.engine.module.ModuleManager
import com.chattriggers.ctjs.internal.launch.SecureLoader
import com.chattriggers.ctjs.internal.utils.Initializer
import kotlinx.serialization.json.Json
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.TitleScreen
import java.io.File
import java.net.URI
import java.net.URLConnection
import kotlin.concurrent.thread

class CTJS : ClientModInitializer {
    override fun onInitializeClient() {
        PictureInPictureRendererRegistry.register { input -> createSkijaPIP(input, pre = false) }
        PictureInPictureRendererRegistry.register { input -> createSkijaPIP(input, pre = true) }
        Client.referenceSystemTime = System.nanoTime()
        Initializer.initializers.forEach(Initializer::init)
        Config.loadData()

        var autoOpenTriggered = false
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val currentScreen = client.screenCompat ?: return@register
            val isMenuScreen = currentScreen is TitleScreen

            if (autoOpenTriggered || Config.wasWelcomeShown() || !isMenuScreen) return@register
            autoOpenTriggered = true
            WelcomeScreen.open()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
            Render2D.destroy()
            TriggerType.GAME_UNLOAD.triggerAll()
            DiscordRPC.turnOff()
            Console.close()
        }

        SecureLoader.onInitialize()
    }

    companion object {
        const val MOD_ID = "ctjs"
        const val WEBSITE_ROOT = "https://www.chattriggers.com"
        const val MOD_VERSION = "5.2.0"
        const val MODULES_FOLDER = "./config/ChatTriggers/modules"

        val configLocation = File("./config")
        val assetsDir = File(configLocation, "ChatTriggers/assets/").apply { mkdirs() }

        @JvmStatic
        @Volatile
        var isLoaded = true
            private set

        @Volatile
        private var isReloading = false

        internal val images = mutableListOf<Image>()
        internal val sounds = mutableListOf<Sound>()
        internal val isDevelopment = FabricLoader.getInstance().isDevelopmentEnvironment

        internal val json = Json {
            useAlternativeNames = true
            ignoreUnknownKeys = true
        }

        @JvmOverloads
        internal fun makeWebRequest(url: String, userAgent: String? = "Mozilla/5.0 (ChatTriggers)"): URLConnection =
            URI(url).toURL().openConnection().apply {
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = 3000
                readTimeout = 3000
            }

        @JvmStatic
        fun unload(asCommand: Boolean = true) {
            Client.unpressKeys()
            TriggerType.WORLD_UNLOAD.triggerAll()
            TriggerType.GAME_UNLOAD.triggerAll()
            Scoreboard.clearCustom()

            isLoaded = false

            ModuleManager.teardown()
            KeyBind.clearKeyBinds()
            Register.clearCustomTriggers()
            StaticCommand.unregisterAll()
            DynamicCommands.unregisterAll()
            Render2D.clearCallbacks()

            if (Config.clearConsoleOnLoad)
                Console.clear()

            Client.scheduleTask {
                Render2D.destroy()
                images.toList().forEach(Image::destroy)
                sounds.toList().forEach(Sound::destroy)
            }

            if (asCommand)
                ChatLib.chat("&7Unloaded ChatTriggers")
        }

        @JvmStatic
        @Synchronized
        fun load(asCommand: Boolean = true) {
            if (isReloading) return
            isReloading = true

            Client.getMinecraft().options.save()
            unload(asCommand = false)

            if (asCommand)
                ChatLib.chat("&cReloading ChatTriggers...")

            thread {
                try {
                    SecureLoader.reload()
                    ModuleManager.setup()
                    Client.getMinecraft().options.load()

                    // Need to set isLoaded to true before running modules, otherwise custom triggers
                    // activated at the top level will not work
                    isLoaded = true

                    ModuleManager.entryPass()

                    if (asCommand)
                        ChatLib.chat("&aDone reloading!")

                    TriggerType.GAME_LOAD.triggerAll()
                    if (World.isLoaded())
                        TriggerType.WORLD_LOAD.triggerAll()
                } finally {
                    isReloading = false
                }
            }
        }
    }
}
