package com.chattriggers.ctjs.api.render

import com.chattriggers.ctjs.api.client.Client
import com.chattriggers.ctjs.api.client.MinecraftCompat
import com.chattriggers.ctjs.api.client.Player
import com.chattriggers.ctjs.api.entity.PlayerMP
import com.chattriggers.ctjs.api.message.ChatLib
import com.chattriggers.ctjs.api.vec.Vec3f
import com.chattriggers.ctjs.engine.LogType
import com.chattriggers.ctjs.engine.printToConsole
import com.chattriggers.ctjs.internal.utils.getOrDefault
import com.chattriggers.ctjs.internal.utils.toRadians
import gg.essential.universal.UMatrixStack
import net.minecraft.client.gui.Font
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.mozilla.javascript.NativeObject
import kotlin.collections.ArrayDeque
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sin

object Render2D : GuiRendererBackend() {
    private val NEWLINE_REGEX = """\n|\r\n?""".toRegex()

    // The currently-active matrix stack
    internal lateinit var matrixStack: UMatrixStack
    private val matrixStackStack = ArrayDeque<UMatrixStack>()

    private lateinit var slimCTRenderPlayer: CTPlayerRenderer
    private lateinit var normalCTRenderPlayer: CTPlayerRenderer

    internal var matrixPushCounter = 0

    @JvmField
    val screen = ScreenWrapper()

    // The current partialTicks value
    @JvmStatic
    var partialTicks = 0f
        internal set

    @JvmField
    val BLACK = getColor(0, 0, 0, 255)

    @JvmField
    val DARK_BLUE = getColor(0, 0, 190, 255)

    @JvmField
    val DARK_GREEN = getColor(0, 190, 0, 255)

    @JvmField
    val DARK_AQUA = getColor(0, 190, 190, 255)

    @JvmField
    val DARK_RED = getColor(190, 0, 0, 255)

    @JvmField
    val DARK_PURPLE = getColor(190, 0, 190, 255)

    @JvmField
    val GOLD = getColor(217, 163, 52, 255)

    @JvmField
    val GRAY = getColor(190, 190, 190, 255)

    @JvmField
    val DARK_GRAY = getColor(63, 63, 63, 255)

    @JvmField
    val BLUE = getColor(63, 63, 254, 255)

    @JvmField
    val GREEN = getColor(63, 254, 63, 255)

    @JvmField
    val AQUA = getColor(63, 254, 254, 255)

    @JvmField
    val RED = getColor(254, 63, 63, 255)

    @JvmField
    val LIGHT_PURPLE = getColor(254, 63, 254, 255)

    @JvmField
    val YELLOW = getColor(254, 254, 63, 255)

    @JvmField
    val WHITE = getColor(255, 255, 255, 255)

    @JvmStatic
    fun color(color: Int): Long {
        return when (color) {
            0 -> BLACK
            1 -> DARK_BLUE
            2 -> DARK_GREEN
            3 -> DARK_AQUA
            4 -> DARK_RED
            5 -> DARK_PURPLE
            6 -> GOLD
            7 -> GRAY
            8 -> DARK_GRAY
            9 -> BLUE
            10 -> GREEN
            11 -> AQUA
            12 -> RED
            13 -> LIGHT_PURPLE
            14 -> YELLOW
            else -> WHITE
        }
    }

    @JvmStatic
    internal fun initializePlayerRenderers(context: EntityRendererProvider.Context) {
        normalCTRenderPlayer = CTPlayerRenderer(context, slim = false)
        slimCTRenderPlayer = CTPlayerRenderer(context, slim = true)
    }

    @JvmStatic
    fun getFontRenderer() = Client.getMinecraft().font

    @JvmStatic
    fun getRenderManager() = Client.getMinecraft().levelRenderer

    @JvmStatic
    fun getStringWidth(text: String) = getFontRenderer().width(ChatLib.addColor(text))

    @JvmStatic
    @JvmOverloads
    fun getColor(red: Int, green: Int, blue: Int, alpha: Int = 255): Long {
        return ((alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)).toLong()
    }

    @JvmStatic
    @JvmOverloads
    fun getRainbow(step: Float, speed: Float = 1f): Long {
        val red = ((sin(step / speed) + 0.75) * 170).toInt()
        val green = ((sin(step / speed + 2 * PI / 3) + 0.75) * 170).toInt()
        val blue = ((sin(step / speed + 4 * PI / 3) + 0.75) * 170).toInt()
        return getColor(red, green, blue)
    }

    @JvmStatic
    @JvmOverloads
    fun getRainbowColors(step: Float, speed: Float = 1f): IntArray {
        val red = ((sin(step / speed) + 0.75) * 170).toInt()
        val green = ((sin(step / speed + 2 * PI / 3) + 0.75) * 170).toInt()
        val blue = ((sin(step / speed + 4 * PI / 3) + 0.75) * 170).toInt()
        return intArrayOf(red, green, blue)
    }

    @JvmStatic
    fun deleteTexture(texture: Image) = apply {
        texture.destroy()
    }

    @JvmStatic
    @JvmOverloads
    fun pushMatrix(stack: UMatrixStack = matrixStack) = apply {
        matrixPushCounter++
        matrixStackStack.addLast(stack)
        matrixStack = stack
        stack.push()
        DrawContextHolder.currentContext?.pose()?.pushMatrix()
    }

    @JvmStatic
    fun popMatrix() = apply {
        matrixPushCounter--
        matrixStackStack.removeLast()
        matrixStack.pop()
        DrawContextHolder.currentContext?.pose()?.popMatrix()
    }

    @JvmStatic
    @JvmOverloads
    fun translate(x: Float, y: Float, z: Float = 0.0F) = apply {
        if (translateSkija(x, y)) return@apply
        DrawContextHolder.currentContext?.pose()?.translate(x, y) ?: matrixStack.translate(x, y, z)
    }

    @JvmStatic
    @JvmOverloads
    fun scale(scaleX: Float, scaleY: Float = scaleX, scaleZ: Float = 1f) = apply {
        if (scaleSkija(scaleX, scaleY)) return@apply
        DrawContextHolder.currentContext?.pose()?.scale(scaleX, scaleY) ?: matrixStack.scale(scaleX, scaleY, scaleZ)
    }

    @JvmStatic
    @JvmOverloads
    fun rotate(angle: Float, x: Float = 0f, y: Float = 0f, z: Float = 1f) = apply {
        if (x == 0f && y == 0f && z != 0f) {
            if (rotateSkija(angle * z)) return@apply
            DrawContextHolder.currentContext?.pose()?.rotate(Math.toRadians((angle * z).toDouble()).toFloat())
                ?: matrixStack.rotate(angle, x, y, z)
        } else matrixStack.rotate(angle, x, y, z)
    }

    @JvmStatic
    fun multiply(quaternion: Quaternionf) = apply {
        matrixStack.multiply(quaternion)
    }

    @JvmStatic
    fun fixAlpha(color: Long): Long {
        val alpha = color ushr 24 and 255
        return if (alpha < 10)
            (color and 0xFF_FF_FF) or 0xA_FF_FF_FF
        else color
    }

    /**
     * Gets a fixed render position from x, y, and z inputs adjusted with partial ticks
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     * @return the Vec3f position to render at
     */
    @JvmStatic
    fun getRenderPos(x: Float, y: Float, z: Float): Vec3f {
        return Vec3f(
            x - Player.getRenderX().toFloat(),
            y - Player.getRenderY().toFloat(),
            z - Player.getRenderZ().toFloat()
        )
    }

    @JvmStatic
    @JvmOverloads
    fun drawString(
        text: String,
        x: Float,
        y: Float,
        color: Long = WHITE,
        shadow: Boolean = false,
    ) {
        val fr = getFontRenderer()
        var newY = y
        DrawContextHolder.currentContext?.let { context ->
            splitText(text).lines.forEach {
                context.text(fr, it, x.roundToInt(), newY.roundToInt(), color.toInt(), shadow)
                newY += fr.lineHeight
            }
            return
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawStringWithShadow(text: String, x: Float, y: Float, color: Long = WHITE) =
        drawString(text, x, y, color, shadow = true)

    internal data class TextLines(val lines: List<String>, val width: Float, val height: Float)

    internal fun splitText(text: String): TextLines {
        val lines = ChatLib.addColor(text).split(NEWLINE_REGEX)
        return TextLines(
            lines,
            lines.maxOf { getFontRenderer().width(it) }.toFloat(),
            (getFontRenderer().lineHeight * lines.size + (lines.size - 1)).toFloat(),
        )
    }

    @JvmStatic
    fun drawImage(image: Image, x: Float, y: Float, width: Float, height: Float) {
        val texture = image.getTexture() ?: return
        DrawContextHolder.currentContext?.blit(
            texture.textureView,
            texture.sampler,
            x.roundToInt(), y.roundToInt(), width.roundToInt(), height.roundToInt(),
            0f, 0f, 1f, 1f,
        )
    }

    /**
     * Draws a player entity to the screen, similar to the one displayed in the inventory screen.
     *
     * Takes a parameter with the following options:
     * - player: The player entity to draw. Can be a [PlayerMP] or [AbstractClientPlayerEntity].
     *           Defaults to Player.toMC()
     * - x: The x position on the screen to render the player
     * - y: The y position on the screen to render the player
     * - size: The size of the rendered player
     * - rotate: Whether the player should look at the mouse cursor, similar to the inventory screen
     * - pitch: THe pitch the rendered player will face, if rotate is false
     * - yaw: The yaw the rendered player will face, if rotate is false
     * - showNametag: Whether the nametag of the player should be rendered
     * - showArmor: Whether the armor of the player should be rendered
     * - showCape: Whether the cape of the player should be rendered
     * - showHeldItem: Whether the held item of the player should be rendered
     * - showArrows: Whether any arrows stuck in the player's model should be rendered
     * - showElytra: Whether the player's Elytra should be rendered
     * - showParrot: Whether a perched parrot should be rendered
     * - showBeeStinger: Whether any stuck bee stingers should be rendered
     *
     * @param obj An options bag
     */
    @JvmStatic
    fun drawPlayer(obj: NativeObject) {
        val entity = obj["player"].let {
            it as? AbstractClientPlayer
                ?: ((it as? PlayerMP)?.toMC() as? AbstractClientPlayer)
                ?: Player.toMC()
                ?: return
        }

        val x = obj.getOrDefault<Number>("x", 0).toInt()
        val y = obj.getOrDefault<Number>("y", 0).toInt()
        val size = obj.getOrDefault<Number>("size", 20).toDouble()
        val rotate = obj.getOrDefault<Boolean>("rotate", false)
        val pitch = obj.getOrDefault<Number>("pitch", 0f).toFloat()
        val yaw = obj.getOrDefault<Number>("yaw", 0f).toFloat()
        val slim = obj.getOrDefault<Boolean>("slim", false)
        val showNametag = obj.getOrDefault<Boolean>("showNametag", false)
        val showArmor = obj.getOrDefault<Boolean>("showArmor", false)
        val showCape = obj.getOrDefault<Boolean>("showCape", false)
        val showHeldItem = obj.getOrDefault<Boolean>("showHeldItem", false)
        val showArrows = obj.getOrDefault<Boolean>("showArrows", false)
        val showElytra = obj.getOrDefault<Boolean>("showElytra", false)
        val showParrot = obj.getOrDefault<Boolean>("showParrot", false)
        val showStingers = obj.getOrDefault<Boolean>("showBeeStinger", false)

        matrixStack.push()

        val (entityYaw, entityPitch) = if (rotate) {
            val mouseX = x - Client.getMouseX()
            val mouseY = y - Client.getMouseY() - (entity.eyeHeight * size)
            atan((mouseX / 40.0f)).toFloat() to atan((mouseY / 40.0f)).toFloat()
        } else {
            val scaleFactor = 130f / 180f
            (yaw * scaleFactor).toRadians() to pitch.toRadians()
        }

        val flipModelRotation = Quaternionf().rotateZ(Math.PI.toFloat())
        val pitchModelRotation =
            Quaternionf().rotateX(entityPitch * 20.0f * (Math.PI / 180.0).toFloat())
        flipModelRotation.mul(pitchModelRotation)

        val oldBodyYaw = entity.yBodyRot
        val oldYaw = entity.yRot
        val oldPitch = entity.xRot
        val oldPrevHeadYaw = entity.yHeadRotO
        val oldHeadYaw = entity.yHeadRot

        entity.yBodyRot = 180.0f + entityYaw * 20.0f
        entity.setYRot(180.0f + entityYaw * 40.0f)
        entity.setXRot(-entityPitch * 20.0f)
        entity.yHeadRot = entity.yRot
        entity.yHeadRotO = entity.yRot

        matrixStack.push()
        matrixStack.translate(0.0, 0.0, 1000.0)
        matrixStack.push()
        matrixStack.translate(x.toDouble(), y.toDouble(), 50.0)

        // UC's version of multiplyPositionMatrix
        matrixStack.peek().model.mul(
            Matrix4f().scaling(
                size.toFloat(),
                size.toFloat(),
                (-size).toFloat()
            )
        )

        matrixStack.multiply(flipModelRotation)
        // TODO: find out a way to get Diffuse instance and call setType
        // DiffuseLighting.enableGuiShaderLighting()

        val entityRenderDispatcher = Client.getMinecraft().entityRenderDispatcher

        if (pitchModelRotation != null) {
            pitchModelRotation.conjugate()
            entityRenderDispatcher.camera?.rotation()?.set(pitchModelRotation)
        }


        // val light = 0xf000f0

        val entityRenderer = if (slim) slimCTRenderPlayer else normalCTRenderPlayer
        entityRenderer.setOptions(
            showNametag,
            showArmor,
            showCape,
            showHeldItem,
            showArrows,
            showElytra,
            showParrot,
            showStingers
        )

        val playerEntityRenderState = entityRenderer.createRenderState().apply {
            this.scale = size.toFloat()
            this.bodyRot = entity.yBodyRot
            this.yRot = entity.yRot
        }

        val vec3d = entityRenderer.getRenderOffset(playerEntityRenderState)
        val d = vec3d.x()
        val e = vec3d.y()
        val f = vec3d.z()
        matrixStack.push()
        matrixStack.translate(d, e, f)

        entityRenderer.submit(
            playerEntityRenderState,
            matrixStack.toMC(),
            MinecraftCompat.submitNodeStorage(Client.getMinecraft().gameRenderer),
            MinecraftCompat.gameRenderState(Client.getMinecraft().gameRenderer).levelRenderState.cameraRenderState,
        )

        matrixStack.pop()

        // entityRenderDispatcher.setRenderShadows(true)
        matrixStack.pop()
        // TODO: find out a way to get Diffuse instance and call setType
        // DiffuseLighting.enableGuiDepthLighting()
        matrixStack.pop()

        entity.yBodyRot = oldBodyYaw
        entity.setYRot(oldYaw)
        entity.setXRot(oldPitch)
        entity.yHeadRotO = oldPrevHeadYaw
        entity.yHeadRot = oldHeadYaw

        matrixStack.pop()
    }

    internal fun withMatrix(stack: PoseStack?, partialTicks: Float = Render2D.partialTicks, block: () -> Unit) {
        Render2D.partialTicks = partialTicks
        matrixPushCounter = 0

        try {
            if (stack != null)
                pushMatrix(UMatrixStack(stack))

            block()
        } finally {
            if (stack != null)
                popMatrix()
        }

        if (matrixPushCounter > 0) {
            "Warning: Render function missing a call to Render2D.popMatrix()".printToConsole(LogType.WARN)
        } else if (matrixPushCounter < 0) {
            "Warning: Render function has too many calls to Render2D.popMatrix()".printToConsole(LogType.WARN)
        }
    }

    class ScreenWrapper {
        fun getWidth(): Int = Client.getMinecraft().window.guiScaledWidth

        fun getHeight(): Int = Client.getMinecraft().window.guiScaledHeight

        fun getScale(): Double = Client.getMinecraft().window.guiScale.toDouble()
    }
}
