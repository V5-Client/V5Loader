package com.chattriggers.ctjs.api.client

//? if >=26.2 {
import com.chattriggers.ctjs.internal.mixins.GameRenderer262Accessor
import com.chattriggers.ctjs.internal.utils.asMixin
//?}
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.components.BossHealthOverlay
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.network.chat.Component

//? if <26.2 {
/*internal object MinecraftCompat {
    fun bossOverlay(gui: Gui): BossHealthOverlay = gui.bossOverlay
    fun setTimes(gui: Gui, fadeIn: Int, stay: Int, fadeOut: Int) = gui.setTimes(fadeIn, stay, fadeOut)
    fun setTitle(gui: Gui, title: Component) = gui.setTitle(title)
    fun setSubtitle(gui: Gui, subtitle: Component) = gui.setSubtitle(subtitle)
    fun toastManager(minecraft: Minecraft): ToastManager = minecraft.toastManager
    fun mainCamera(renderer: GameRenderer): Camera = renderer.mainCamera
    fun mainRenderTarget(minecraft: Minecraft): RenderTarget = minecraft.mainRenderTarget
    fun reloadLevelRenderer(minecraft: Minecraft) = minecraft.levelRenderer.allChanged()
    fun submitNodeStorage(renderer: GameRenderer): SubmitNodeStorage = renderer.submitNodeStorage
    fun gameRenderState(renderer: GameRenderer): GameRenderState = renderer.gameRenderState
    fun isSingleplayer(minecraft: Minecraft): Boolean = minecraft.isSingleplayer
}

internal val Minecraft.screenCompat: Screen? get() = screen
internal val Minecraft.overlayCompat: Overlay? get() = overlay
internal fun Minecraft.setScreenCompat(screen: Screen?) = setScreen(screen)
internal val Gui.chatCompat: ChatComponent get() = chat
internal val Gui.tabListCompat: PlayerTabOverlay get() = tabList
*///?} else {
internal object MinecraftCompat {
    fun bossOverlay(gui: Gui): BossHealthOverlay = gui.hud.bossOverlay
    fun setTimes(gui: Gui, fadeIn: Int, stay: Int, fadeOut: Int) = gui.hud.setTimes(fadeIn, stay, fadeOut)
    fun setTitle(gui: Gui, title: Component) = gui.hud.setTitle(title)
    fun setSubtitle(gui: Gui, subtitle: Component) = gui.hud.setSubtitle(subtitle)
    fun toastManager(minecraft: Minecraft): ToastManager = minecraft.gui.toastManager()
    fun mainCamera(renderer: GameRenderer): Camera = renderer.mainCamera()
    fun mainRenderTarget(minecraft: Minecraft): RenderTarget = minecraft.gameRenderer.mainRenderTarget()
    fun reloadLevelRenderer(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        minecraft.levelRenderer.invalidateCompiledGeometry(level, minecraft.options, mainCamera(minecraft.gameRenderer), minecraft.blockColors)
    }
    fun submitNodeStorage(renderer: GameRenderer): SubmitNodeStorage =
        renderer.asMixin<GameRenderer262Accessor>().handAndScreenSubmitNodeStorage
    fun gameRenderState(renderer: GameRenderer): GameRenderState = renderer.gameRenderState()
    fun isSingleplayer(minecraft: Minecraft): Boolean = minecraft.hasSingleplayerServer()
}

internal val Minecraft.screenCompat: Screen? get() = gui.screen()
internal val Minecraft.overlayCompat: Overlay? get() = gui.overlay()
internal fun Minecraft.setScreenCompat(screen: Screen?) = gui.setScreen(screen)
internal val Gui.chatCompat: ChatComponent get() = hud.chat
internal val Gui.tabListCompat: PlayerTabOverlay get() = hud.tabList
//?}
