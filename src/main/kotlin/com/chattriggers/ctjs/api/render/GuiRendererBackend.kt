package com.chattriggers.ctjs.api.render

import com.chattriggers.ctjs.internal.mixins.GuiGraphicsExtractorAccessor
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Matrix3x2f
import org.lwjgl.nanovg.NanoSVG.nsvgCreateRasterizer
import org.lwjgl.nanovg.NanoSVG.nsvgDelete
import org.lwjgl.nanovg.NanoSVG.nsvgDeleteRasterizer
import org.lwjgl.nanovg.NanoSVG.nsvgParse
import org.lwjgl.nanovg.NanoSVG.nsvgRasterize
import org.lwjgl.system.MemoryUtil
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Minecraft GUI extraction and GPU resource implementation for [Renderer]. */
open class GuiRendererBackend {
    private val mc = Minecraft.getInstance()
    private val preRenderCallbacks = CopyOnWriteArrayList<Runnable>()
    private val renderCallbacks = CopyOnWriteArrayList<Runnable>()
    private val imageCache = HashMap<String, CachedTexture>()
    private val generatedTextureCache = HashMap<String, CachedTexture>()
    private val gifCache = HashMap<String, CachedGif>()
    private val urlCache = ConcurrentHashMap<String, CachedTexture?>()
    private val pendingDownloads = ConcurrentHashMap.newKeySet<String>()
    private val failedDownloads = ConcurrentHashMap.newKeySet<String>()
    private val downloadQueue = LinkedBlockingQueue<Pair<String, NativeImage?>>()
    private val decodeExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "V5 image decoder").apply { isDaemon = true }
    }
    private val decodedImages = ConcurrentLinkedQueue<Pair<String, NativeImage?>>()
    private val pendingImages = HashMap<String, Int>()
    private val failedImages = HashSet<String>()
    private val decodedGifs = ConcurrentLinkedQueue<Pair<String, DecodedGif?>>()
    private val pendingGifs = HashMap<String, Int>()
    private val failedGifs = HashSet<String>()
    private val vertexPool = ArrayDeque<FloatArray>()
    private val inFlightVertices = ArrayDeque<FloatArray>()
    @Volatile private var textEngine: FreeTypeTextRenderer? = FreeTypeTextRenderer()
    @Volatile private var destroyed = false

    private var activeContext: GuiGraphicsExtractor? = null
    private val context get() = activeContext ?: DrawContextHolder.currentContext
    private var alpha = 1f
    private var clip: Clip? = null
    private val states = ArrayDeque<SavedState>()

    @JvmField
    val defaultFont = Font("Default", "/assets/v5/font.otf")

    @JvmField val ALIGN_CENTER = 2
    @JvmField val ALIGN_MIDDLE = 16

    data class GifData(val width: Int, val height: Int, val frameCount: Int, val delays: IntArray) {
        override fun equals(other: Any?) = other is GifData && width == other.width && height == other.height &&
            frameCount == other.frameCount && delays.contentEquals(other.delays)
        override fun hashCode() = 31 * (31 * (31 * width + height) + frameCount) + delays.contentHashCode()
    }

    private data class CachedTexture(val texture: DynamicTexture, var refs: Int = 1) : AutoCloseable {
        val setup = TextureSetup.singleTexture(texture.textureView, texture.sampler)
        override fun close() = texture.close()
    }

    private data class CachedGif(
        val frames: List<CachedTexture>,
        val delays: IntArray,
        val width: Int,
        val height: Int,
        var refs: Int = 1,
    )
    private data class DecodedGif(val frames: List<NativeImage>, val delays: IntArray, val width: Int, val height: Int)

    private data class Clip(val local: FloatArray, val screen: ScreenRectangle)
    private data class SavedState(val alpha: Float, val clip: Clip?)
    private enum class Gradient { LEFT_TO_RIGHT, TOP_TO_BOTTOM, TL_TO_BR, BL_TO_TR }

    private class QuadState(
        private val x: Float,
        private val y: Float,
        private val w: Float,
        private val h: Float,
        private val c0: Int,
        private val c1: Int,
        private val c2: Int,
        private val c3: Int,
        private val pose: Matrix3x2f,
        private val textured: Boolean,
        private val setup: TextureSetup,
        private val scissor: ScreenRectangle?,
        private val area: ScreenRectangle,
    ) : GuiElementRenderState {
        override fun buildVertices(buffer: VertexConsumer) {
            for (i in 0..3) {
                val consumer = buffer.addVertexWith2DPose(
                    pose,
                    if (i >= 2) x + w else x,
                    if (i == 1 || i == 2) y + h else y,
                )
                if (textured) consumer.setUv(if (i >= 2) 1f else 0f, if (i == 1 || i == 2) 1f else 0f)
                consumer.setColor(when (i) { 0 -> c0; 1 -> c1; 2 -> c2; else -> c3 })
            }
        }

        override fun pipeline() = if (textured) RenderPipelines.GUI_TEXTURED else RenderPipelines.GUI
        override fun textureSetup() = setup
        override fun scissorArea() = scissor
        override fun bounds() = area
    }

    private class MeshState(
        private val vertices: FloatArray,
        private val count: Int,
        private val pose: Matrix3x2f,
        private val textured: Boolean,
        private val setup: TextureSetup,
        private val scissor: ScreenRectangle?,
        private val area: ScreenRectangle,
    ) : GuiElementRenderState {
        override fun buildVertices(buffer: VertexConsumer) {
            for (i in 0 until count) {
                val offset = i * 5
                val consumer = buffer.addVertexWith2DPose(pose, vertices[offset], vertices[offset + 1])
                if (textured) consumer.setUv(vertices[offset + 2], vertices[offset + 3])
                consumer.setColor(vertices[offset + 4].toRawBits())
            }
        }

        override fun pipeline() = if (textured) RenderPipelines.GUI_TEXTURED else RenderPipelines.GUI
        override fun textureSetup() = setup
        override fun scissorArea() = scissor
        override fun bounds() = area
    }

    fun registerV5Render(runnable: Runnable) { renderCallbacks += runnable }
    fun unregisterV5Render(runnable: Runnable) { renderCallbacks -= runnable }
    fun registerV5PreRender(runnable: Runnable) { preRenderCallbacks += runnable }
    fun unregisterV5PreRender(runnable: Runnable) { preRenderCallbacks -= runnable }
    fun clearCallbacks() { renderCallbacks.clear(); preRenderCallbacks.clear() }

    
    fun runPreDrawables(context: GuiGraphicsExtractor) {
        synchronized(vertexPool) {
            vertexPool.addAll(inFlightVertices)
            inFlightVertices.clear()
        }
        withContext(context) { runCallbacks(preRenderCallbacks) }
    }

    
    fun runDrawables(context: GuiGraphicsExtractor) = withContext(context) {
        processImageQueues()
        textEngine?.processUploads()
        runCallbacks(renderCallbacks)
    }

    private inline fun withContext(drawContext: GuiGraphicsExtractor, block: () -> Unit) {
        val previous = DrawContextHolder.currentContext
        DrawContextHolder.currentContext = drawContext
        activeContext = drawContext
        alpha = 1f
        clip = null
        states.clear()
        try { block() } finally {
            while (states.isNotEmpty()) restore()
            if (clip != null) drawContext.disableScissor()
            alpha = 1f
            clip = null
            activeContext = null
            DrawContextHolder.currentContext = previous
        }
    }

    private fun runCallbacks(callbacks: List<Runnable>) = callbacks.forEach {
        try { it.run() } catch (e: Exception) { e.printStackTrace() }
    }

    fun blurBackground() { context?.blurBeforeThisStratum() }
    fun save() { context?.pose()?.pushMatrix(); states.addLast(SavedState(alpha, clip)) }
    fun restore() {
        val saved = states.removeLastOrNull() ?: return
        val changedClip = clip != saved.clip
        context?.pose()?.popMatrix()
        if (changedClip) {
            if (clip != null) context?.disableScissor()
            if (clip == null) saved.clip?.local?.let {
                context?.enableScissor(it[0].roundToInt(), it[1].roundToInt(), (it[0] + it[2]).roundToInt(), (it[1] + it[3]).roundToInt())
            }
        }
        alpha = saved.alpha
        clip = saved.clip
    }
    fun globalAlpha(value: Float) { alpha = value.coerceIn(0f, 1f) }

    fun scissor(x: Float, y: Float, w: Float, h: Float) {
        val ctx = context ?: return
        val local = floatArrayOf(x, y, w, h)
        val transformed = ScreenRectangle(x.toInt(), y.toInt(), ceil(w.toDouble()).toInt(), ceil(h.toDouble()).toInt())
            .transformMaxBounds(Matrix3x2f(ctx.pose()))
        val intersected = clip?.screen?.intersection(transformed) ?: transformed
        ctx.enableScissor(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())
        clip = Clip(local, intersected)
    }

    fun resetScissor() { if (clip != null) context?.disableScissor(); clip = null }
    fun pushScissor(x: Float, y: Float, w: Float, h: Float) { save(); scissor(x, y, w, h) }
    fun popScissor() = restore()

    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: Int) =
        emitQuad(x, y, w, h, applyAlpha(color))

    fun drawRoundedRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) =
        if (radius <= 0f) drawRect(x, y, w, h, color) else emitFill(x, y, w, h, radius, radius, radius, radius, applyAlpha(color))

    fun drawRoundedRectVaried(x: Float, y: Float, w: Float, h: Float, color: Int, tl: Float, tr: Float, br: Float, bl: Float) =
        if (max(max(tl, tr), max(br, bl)) <= 0f) drawRect(x, y, w, h, color)
        else emitFill(x, y, w, h, tl, tr, br, bl, applyAlpha(color))

    fun drawCircle(x: Float, y: Float, radius: Float, color: Int) =
        drawRoundedRect(x - radius, y - radius, radius * 2, radius * 2, radius, color)

    
    @JvmOverloads
    fun drawHollowRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color: Int, radius: Float = 0f) {
        val t = thickness.coerceAtLeast(0.1f)
        emitRing(x, y, w, h, radius, x + t, y + t, w - 2 * t, h - 2 * t, max(0f, radius - t), applyAlpha(color))
    }

    
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length == 0f) return drawCircle(x1, y1, thickness / 2f, color)
        val nx = -dy / length * thickness / 2f
        val ny = dx / length * thickness / 2f
        val c = applyAlpha(color)
        emitQuadPoints(x1 + nx, y1 + ny, x1 - nx, y1 - ny, x2 - nx, y2 - ny, x2 + nx, y2 + ny, c)
        drawCircle(x1, y1, thickness / 2f, color)
        drawCircle(x2, y2, thickness / 2f, color)
    }

    
    fun drawDropShadow(x: Float, y: Float, w: Float, h: Float, radius: Float, blur: Float, spread: Float, color: Int) {
        val steps = max(2, ceil(blur.coerceAtLeast(1f) / 2f).toInt())
        var ix = x - spread
        var iy = y - spread
        var iw = w + 2 * spread
        var ih = h + 2 * spread
        var ir = radius + spread
        for (step in 1..steps) {
            val distance = blur * step / steps
            val ox = x - spread - distance
            val oy = y - spread - distance
            val ow = w + 2 * (spread + distance)
            val oh = h + 2 * (spread + distance)
            val outerRadius = radius + spread + distance
            val fade = 1f - step.toFloat() / (steps + 1)
            emitRing(ix, iy, iw, ih, ir, ox, oy, ow, oh, outerRadius, applyAlpha(withAlpha(color, (((color ushr 24) and 255) * fade).roundToInt())))
            ix = ox; iy = oy; iw = ow; ih = oh; ir = outerRadius
        }
    }

    
    @JvmOverloads
    fun drawGradientRect(x: Float, y: Float, w: Float, h: Float, color1: Int, color2: Int, direction: Any, radius: Float = 0f) {
        val gradient = resolveGradient(direction)
        if (radius <= 0f) emitGradientQuad(x, y, w, h, color1, color2, gradient)
        else emitFill(x, y, w, h, radius, radius, radius, radius, color1, color2, gradient)
    }

    
    @JvmOverloads
    fun drawHollowGradientRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color1: Int, color2: Int, direction: Any, radius: Float = 0f) {
        val t = thickness.coerceAtLeast(0.1f)
        emitRing(x, y, w, h, radius, x + t, y + t, w - 2 * t, h - 2 * t, max(0f, radius - t), color1, color2, resolveGradient(direction))
    }

    
    @JvmOverloads
    fun drawCheckerboard(x: Float, y: Float, w: Float, h: Float, radius: Float, size: Float = 4f) {
        val width = ceil(w.toDouble()).toInt().coerceAtLeast(1)
        val height = ceil(h.toDouble()).toInt().coerceAtLeast(1)
        val step = size.roundToInt().coerceAtLeast(1)
        val key = "checker:$width:$height:$step"
        val texture = generatedTextureCache.getOrPut(key) {
            val image = NativeImage(width, height, false)
            for (py in 0 until height) for (px in 0 until width) {
                image.setPixel(px, py, if ((px / step + py / step) % 2 == 0) 0xff404040.toInt() else 0xff737373.toInt())
            }
            createTexture("V5 checkerboard", image)
        }
        drawTexture(texture, x, y, w, h, radius, 1f)
    }

    
    fun drawHueBar(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        val texture = generatedTextureCache.getOrPut("hue") {
            val image = NativeImage(256, 1, false)
            for (px in 0 until 256) {
                image.setPixel(px, 0, 0xff000000.toInt() or (Color.HSBtoRGB(px / 255f, 1f, 1f) and 0xffffff))
            }
            createTexture("V5 hue bar", image)
        }
        drawTexture(texture, x, y, w, h, radius, 1f)
    }

    fun getDefaultFont() = defaultFont

    
    @JvmOverloads
    fun text(text: String, x: Float, y: Float, size: Float, color: Int, font: Font? = defaultFont, align: Int) {
        val ctx = context ?: return
        val resolvedFont = font ?: defaultFont
        val renderer = textRenderer()
        val scale = size / 8f
        val pose = Matrix3x2f(ctx.pose())
        val framebufferScale = mc.window.guiScale * scale * max(
            hypot(pose.m00().toDouble(), pose.m01().toDouble()),
            hypot(pose.m10().toDouble(), pose.m11().toDouble()),
        )
        val pixelSize = ceil(8.0 * framebufferScale).toInt().coerceIn(1, 512)
        val layout = renderer.layout(text, resolvedFont, pixelSize) ?: return
        val localY = when {
            align and 32 != 0 -> -layout.height
            align and 16 != 0 -> -layout.height / 2f
            align and 8 != 0 -> 0f
            else -> -layout.ascender
        }
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)
        emitText(renderer, layout, resolvedFont, pixelSize, localY, align, applyAlpha(color))
        ctx.pose().popMatrix()
    }

    
    @JvmOverloads
    fun textWidth(text: String, size: Float, font: Font? = defaultFont): Float =
        textRenderer().measure(text, font ?: defaultFont).width * size / 8f

    
    fun loadImage(path: String): String {
        if (path.isRemoteUrl()) return path
        synchronized(imageCache) {
            imageCache[path]?.let { it.refs++; return path }
            if (path in failedImages) return path
            pendingImages[path]?.let { pendingImages[path] = it + 1; return path }
            pendingImages[path] = 1
        }
        decodeExecutor.execute {
            val image = try {
                if (path.endsWith(".svg", true)) loadSvg(readImage(path), path) else NativeImage.read(readImage(path))
            } catch (e: Exception) {
                println("[V5] Failed to load image $path: ${e.message}"); null
            }
            if (destroyed) image?.close() else decodedImages += path to image
        }
        return path
    }

    fun unloadImage(path: String) = synchronized(imageCache) {
        imageCache[path]?.let { if (--it.refs <= 0) { it.close(); imageCache.remove(path) } }
        pendingImages[path]?.let { pendingImages[path] = max(0, it - 1) }
    }
    fun isImageLoaded(path: String) = synchronized(imageCache) { path in imageCache }

    
    @JvmOverloads
    fun drawImage(path: String, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f) {
        if (path.isRemoteUrl()) return drawImageFromUrl(path, x, y, w, h, radius, alpha)
        val texture = synchronized(imageCache) { imageCache[path] }
        if (texture == null) { ensureImageRequested(path); return }
        drawTexture(texture, x, y, w, h, radius, alpha)
    }

    private fun ensureImageRequested(path: String) {
        synchronized(imageCache) {
            if (path in imageCache || path in pendingImages || path in failedImages) return
        }
        loadImage(path)
    }

    
    @JvmOverloads
    fun drawImageFromUrl(url: String, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f) {
        if (url == "none" || url in failedDownloads) return
        urlCache[url]?.let { return drawTexture(it, x, y, w, h, radius, alpha) }
        if (pendingDownloads.add(url)) Thread({
            val image = try {
                URI.create(url).toURL().openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000; setRequestProperty("User-Agent", "V5-Loader")
                }.getInputStream().use(NativeImage::read)
            } catch (_: Exception) { null }
            downloadQueue += url to image
        }, "V5 image ${url.hashCode()}").start()
    }

    private fun processImageQueues() {
        for (ignored in 0 until 5) {
            val (url, image) = downloadQueue.poll() ?: break
            if (image == null) failedDownloads += url else urlCache[url] = createTexture("V5 URL $url", image)
            pendingDownloads -= url
        }
        repeat(5) {
            val (path, image) = decodedImages.poll() ?: return@repeat
            synchronized(imageCache) {
                val refs = pendingImages.remove(path) ?: 0
                if (image == null) failedImages += path
                else if (refs > 0) imageCache[path] = createTexture("V5 image $path", image).also { it.refs = refs }
                else image.close()
            }
        }
        decodedGifs.poll()?.let { (path, decoded) ->
            val refs = synchronized(gifCache) { pendingGifs.remove(path) ?: 0 }
            if (decoded == null) failedGifs += path
            else if (refs > 0) gifCache[path] = CachedGif(
                decoded.frames.mapIndexed { index, image -> createTexture("V5 GIF $path#$index", image) },
                decoded.delays, decoded.width, decoded.height, refs,
            ) else decoded.frames.forEach(NativeImage::close)
        }
    }

    
    fun loadGif(path: String): GifData? {
        synchronized(gifCache) {
            gifCache[path]?.let { it.refs++; return GifData(it.width, it.height, it.frames.size, it.delays) }
            if (path in pendingGifs || path in failedGifs) return null
            pendingGifs[path] = 1
        }
        decodeExecutor.execute {
            val gif = decodeGif(path)
            if (destroyed) gif?.frames?.forEach(NativeImage::close) else decodedGifs += path to gif
        }
        return null
    }

    private fun decodeGif(path: String): DecodedGif? = try {
        FileInputStream(File(path)).use { stream ->
                val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull() ?: return null
                reader.input = ImageIO.createImageInputStream(stream)
                val count = reader.getNumImages(true)
                val first = reader.read(0)
                val master = BufferedImage(first.width, first.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = master.createGraphics().apply { background = Color(0, 0, 0, 0) }
                val frames = ArrayList<NativeImage>(count)
                val delays = IntArray(count)
                for (i in 0 until count) {
                    val frame = reader.read(i)
                    val tree = reader.getImageMetadata(i).getAsTree(reader.getImageMetadata(i).nativeMetadataFormatName) as IIOMetadataNode
                    val gce = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                    val desc = tree.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                    val fx = desc.getAttribute("imageLeftPosition").toInt()
                    val fy = desc.getAttribute("imageTopPosition").toInt()
                    delays[i] = (gce.getAttribute("delayTime").toInt() * 10).coerceAtLeast(10)
                    graphics.drawImage(frame, fx, fy, null)
                    frames += master.toNativeImage()
                    if (gce.getAttribute("disposalMethod") == "restoreToBackgroundColor") graphics.clearRect(fx, fy, frame.width, frame.height)
                }
                graphics.dispose(); reader.dispose()
                DecodedGif(frames, delays, first.width, first.height)
            }
        } catch (e: Exception) { println("[V5] Failed to load GIF $path: ${e.message}"); null }

    fun unloadGif(path: String) = synchronized(gifCache) {
        gifCache[path]?.let { if (--it.refs <= 0) { it.frames.forEach(CachedTexture::close); gifCache.remove(path) } }
        pendingGifs[path]?.let { pendingGifs[path] = max(0, it - 1) }
    }

    
    @JvmOverloads
    fun drawGif(path: String, x: Float, y: Float, w: Float, h: Float, frameIndex: Int, radius: Float = 0f, alpha: Float = 1f) {
        val frames = gifCache[path]?.frames ?: return
        if (frames.isNotEmpty()) drawTexture(frames[Math.floorMod(frameIndex, frames.size)], x, y, w, h, radius, alpha)
    }

    private fun drawTexture(texture: CachedTexture, x: Float, y: Float, w: Float, h: Float, radius: Float, imageAlpha: Float) {
        val color = applyAlpha(withAlpha(0xffffffff.toInt(), (255 * imageAlpha.coerceIn(0f, 1f)).roundToInt()))
        if (radius <= 0f) emitQuad(x, y, w, h, color, textured = true, setup = texture.setup)
        else emitFill(x, y, w, h, radius, radius, radius, radius, color, textured = true, setup = texture.setup)
    }

    private fun readImage(path: String): ByteArray {
        val trimmed = path.trim()
        require(!trimmed.isRemoteUrl()) { "Remote images must use drawImageFromUrl" }
        val stream = when {
            File(trimmed).isFile -> FileInputStream(trimmed)
            else -> GuiRendererBackend::class.java.getResourceAsStream(trimmed) ?: throw FileNotFoundException(trimmed)
        }
        return stream.use { it.readBytes() }
    }

    private fun String.isRemoteUrl() = startsWith("http://", true) || startsWith("https://", true)

    private fun loadSvg(bytes: ByteArray, name: String): NativeImage {
        val svg = nsvgParse(String(bytes), "px", 96f) ?: error("Invalid SVG: $name")
        val width = svg.width().roundToInt().coerceAtLeast(1)
        val height = svg.height().roundToInt().coerceAtLeast(1)
        val pixels = MemoryUtil.memAlloc(width * height * 4)
        val rasterizer = nsvgCreateRasterizer()
        try {
            nsvgRasterize(rasterizer, svg, 0f, 0f, 1f, pixels, width, height, width * 4)
            val image = NativeImage(width, height, false)
            for (y in 0 until height) for (x in 0 until width) {
                val i = (y * width + x) * 4
                image.setPixel(x, y, ((pixels.get(i + 3).toInt() and 255) shl 24) or ((pixels.get(i).toInt() and 255) shl 16) or
                    ((pixels.get(i + 1).toInt() and 255) shl 8) or (pixels.get(i + 2).toInt() and 255))
            }
            return image
        } finally { nsvgDeleteRasterizer(rasterizer); nsvgDelete(svg); MemoryUtil.memFree(pixels) }
    }

    private fun BufferedImage.toNativeImage(): NativeImage {
        val native = NativeImage(width, height, false)
        for (y in 0 until height) for (x in 0 until width) native.setPixel(x, y, getRGB(x, y))
        return native
    }

    private fun createTexture(name: String, image: NativeImage) = CachedTexture(DynamicTexture({ name }, image))

    fun clearImageCache() {
        synchronized(imageCache) {
            imageCache.values.forEach(CachedTexture::close); imageCache.clear()
            pendingImages.clear(); failedImages.clear()
        }
        generatedTextureCache.values.forEach(CachedTexture::close); generatedTextureCache.clear()
        synchronized(gifCache) {
            gifCache.values.flatMap { it.frames }.forEach(CachedTexture::close); gifCache.clear()
            pendingGifs.clear(); failedGifs.clear()
        }
        while (true) (decodedImages.poll() ?: break).second?.close()
        while (true) (decodedGifs.poll() ?: break).second?.frames?.forEach(NativeImage::close)
        urlCache.values.filterNotNull().forEach(CachedTexture::close); urlCache.clear(); pendingDownloads.clear(); failedDownloads.clear()
    }
    fun getCacheStats() = "Images: ${imageCache.size}, GIFs: ${gifCache.size}, URLs: ${urlCache.size}"
    fun destroy() {
        destroyed = true
        decodeExecutor.shutdownNow()
        synchronized(this) { textEngine?.close(); textEngine = null }
        clearImageCache()
    }

    private fun textRenderer(): FreeTypeTextRenderer = textEngine ?: synchronized(this) {
        textEngine ?: FreeTypeTextRenderer().also { textEngine = it }
    }

    private fun emitText(renderer: FreeTypeTextRenderer, layout: FreeTypeTextRenderer.Layout, font: Font, pixelSize: Int, y: Float, align: Int, color: Int) {
        val ctx = context ?: return
        val pose = Matrix3x2f(ctx.pose())
        val bitmapScale = 8f / pixelSize
        var vertices: FloatArray? = null
        var count = 0
        var page: FreeTypeTextRenderer.AtlasPage? = null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val glyphs = renderer.glyphs(layout, font, pixelSize)

        fun flush() {
            val data = vertices ?: return
            val atlas = page ?: return releaseFloats(data)
            if (count > 0) emitMesh(data, count, true, atlas.setup, minX, minY, maxX, maxY, pose) else releaseFloats(data)
            vertices = null; count = 0; page = null
            minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
            maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
        }

        for (i in layout.codePoints.indices) {
            val glyph = glyphs[i] ?: continue
            if (glyph.page !== page) { flush(); page = glyph.page; vertices = acquireFloats(4 * 5 * 16) }
            if (count + 4 > vertices!!.size / 5) {
                val old = vertices!!
                vertices = acquireFloats(old.size * 2).also { old.copyInto(it) }
                releaseFloats(old)
            }
            val lineWidth = layout.lineWidths[layout.lines[i]]
            val lineX = when {
                align and 4 != 0 -> -lineWidth
                align and 2 != 0 -> -lineWidth / 2f
                else -> 0f
            }
            val x0 = lineX + layout.xs[i] + glyph.left * bitmapScale
            val baseline = y + layout.ascender + layout.lines[i] * layout.lineHeight
            val y0 = baseline - glyph.top * bitmapScale
            val x1 = x0 + glyph.width * bitmapScale
            val y1 = y0 + glyph.height * bitmapScale
            minX = min(minX, x0); minY = min(minY, y0); maxX = max(maxX, x1); maxY = max(maxY, y1)
            count = putText(vertices!!, count, x0, y0, glyph.u0, glyph.v0, color)
            count = putText(vertices!!, count, x0, y1, glyph.u0, glyph.v1, color)
            count = putText(vertices!!, count, x1, y1, glyph.u1, glyph.v1, color)
            count = putText(vertices!!, count, x1, y0, glyph.u1, glyph.v0, color)
        }
        flush()
    }

    private fun putText(vertices: FloatArray, index: Int, x: Float, y: Float, u: Float, v: Float, color: Int): Int {
        val offset = index * 5
        vertices[offset] = x; vertices[offset + 1] = y; vertices[offset + 2] = u; vertices[offset + 3] = v
        vertices[offset + 4] = Float.fromBits(color)
        return index + 1
    }

    private fun emitFill(
        x: Float, y: Float, w: Float, h: Float,
        tl: Float, tr: Float, br: Float, bl: Float,
        color1: Int, color2: Int = color1, gradient: Gradient? = null,
        textured: Boolean = false, setup: TextureSetup = TextureSetup.noTexture(),
    ) {
        val ctx = context ?: return
        if (w <= 0f || h <= 0f) return
        val pose = Matrix3x2f(ctx.pose())
        val maxRadius = min(w, h) / 2f
        val rtl = tl.coerceIn(0f, maxRadius)
        val rtr = tr.coerceIn(0f, maxRadius)
        val rbr = br.coerceIn(0f, maxRadius)
        val rbl = bl.coerceIn(0f, maxRadius)
        val count = contourPointCount(rtl, rtr, rbr, rbl, pose)
        val points = acquireFloats(count * 2)
        val outer = acquireFloats(count * 2)
        writeContour(points, outer, x, y, w, h, rtl, rtr, rbr, rbl, pose)
        val vertices = acquireFloats(count * 8 * 5)
        var vertexCount = 0
        val cx = x + w / 2f
        val cy = y + h / 2f
        for (i in 0 until count) {
            val next = (i + 1) % count
            val ax = points[i * 2]; val ay = points[i * 2 + 1]
            val bx = points[next * 2]; val by = points[next * 2 + 1]
            val oax = outer[i * 2]; val oay = outer[i * 2 + 1]
            val obx = outer[next * 2]; val oby = outer[next * 2 + 1]
            vertexCount = put(vertices, vertexCount, cx, cy, sampleColor(cx, cy, x, y, w, h, color1, color2, gradient), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, bx, by, sampleColor(bx, by, x, y, w, h, color1, color2, gradient), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, ax, ay, sampleColor(ax, ay, x, y, w, h, color1, color2, gradient), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, ax, ay, sampleColor(ax, ay, x, y, w, h, color1, color2, gradient), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, oax, oay, withAlpha(sampleColor(oax, oay, x, y, w, h, color1, color2, gradient), 0), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, ax, ay, sampleColor(ax, ay, x, y, w, h, color1, color2, gradient), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, bx, by, sampleColor(bx, by, x, y, w, h, color1, color2, gradient), x, y, w, h, textured)
            vertexCount = put(vertices, vertexCount, obx, oby, withAlpha(sampleColor(obx, oby, x, y, w, h, color1, color2, gradient), 0), x, y, w, h, textured)
        }
        releaseFloats(points); releaseFloats(outer)
        emitMesh(vertices, vertexCount, textured, setup, x - localPixel(pose), y - localPixel(pose), x + w + localPixel(pose), y + h + localPixel(pose), pose)
    }

    private fun emitRing(
        ax: Float, ay: Float, aw: Float, ah: Float, ar: Float,
        bx: Float, by: Float, bw: Float, bh: Float, br: Float,
        color: Int,
    ) = emitRing(ax, ay, aw, ah, ar, bx, by, bw, bh, br, color, color, null)

    private fun emitRing(
        ax: Float, ay: Float, aw: Float, ah: Float, ar: Float,
        bx: Float, by: Float, bw: Float, bh: Float, br: Float,
        color1: Int, color2: Int, gradient: Gradient?,
    ) {
        val ctx = context ?: return
        if (aw <= 0f || ah <= 0f || bw <= 0f || bh <= 0f) return
        val pose = Matrix3x2f(ctx.pose())
        val radiusA = ar.coerceIn(0f, min(aw, ah) / 2f)
        val radiusB = br.coerceIn(0f, min(bw, bh) / 2f)
        val segments = cornerSegments(max(radiusA, radiusB), pose)
        val count = 4 * (segments + 1)
        val a = acquireFloats(count * 2)
        val b = acquireFloats(count * 2)
        writeContour(a, null, ax, ay, aw, ah, radiusA, radiusA, radiusA, radiusA, pose, segments)
        writeContour(b, null, bx, by, bw, bh, radiusB, radiusB, radiusB, radiusB, pose, segments)
        val vertices = acquireFloats(count * 4 * 5)
        var vertexCount = 0
        for (i in 0 until count) {
            val next = (i + 1) % count
            val aix = a[i * 2]; val aiy = a[i * 2 + 1]
            val bix = b[i * 2]; val biy = b[i * 2 + 1]
            val anx = a[next * 2]; val any = a[next * 2 + 1]
            val bnx = b[next * 2]; val bny = b[next * 2 + 1]
            vertexCount = put(vertices, vertexCount, aix, aiy, sampleColor(aix, aiy, ax, ay, aw, ah, color1, color2, gradient))
            vertexCount = put(vertices, vertexCount, bix, biy, sampleColor(bix, biy, ax, ay, aw, ah, color1, color2, gradient))
            vertexCount = put(vertices, vertexCount, bnx, bny, sampleColor(bnx, bny, ax, ay, aw, ah, color1, color2, gradient))
            vertexCount = put(vertices, vertexCount, anx, any, sampleColor(anx, any, ax, ay, aw, ah, color1, color2, gradient))
        }
        releaseFloats(a); releaseFloats(b)
        emitMesh(vertices, vertexCount, false, TextureSetup.noTexture(), min(ax, bx), min(ay, by), max(ax + aw, bx + bw), max(ay + ah, by + bh), pose)
    }

    private fun contourPointCount(tl: Float, tr: Float, br: Float, bl: Float, pose: Matrix3x2f) =
        cornerSegments(tl, pose) + cornerSegments(tr, pose) + cornerSegments(br, pose) + cornerSegments(bl, pose) + 4

    private fun cornerSegments(radius: Float, pose: Matrix3x2f): Int {
        val renderedRadius = radius * max(
            hypot(pose.m00().toDouble(), pose.m01().toDouble()),
            hypot(pose.m10().toDouble(), pose.m11().toDouble()),
        ) * mc.window.guiScale
        return ceil(PI * renderedRadius / 4.0).toInt().coerceIn(1, 32)
    }

    private fun writeContour(
        points: FloatArray, outer: FloatArray?, x: Float, y: Float, w: Float, h: Float,
        tl: Float, tr: Float, br: Float, bl: Float, pose: Matrix3x2f, fixedSegments: Int = 0,
    ) {
        var point = 0
        for (corner in 0..3) {
            val radius = when (corner) { 0 -> tl; 1 -> tr; 2 -> br; else -> bl }
            val cx = when (corner) { 0, 3 -> x + radius; else -> x + w - radius }
            val cy = when (corner) { 0, 1 -> y + radius; else -> y + h - radius }
            val start = when (corner) { 0 -> PI; 1 -> -PI / 2; 2 -> 0.0; else -> PI / 2 }
            val segments = if (fixedSegments > 0) fixedSegments else cornerSegments(radius, pose)
            for (i in 0..segments) {
                val angle = start + PI * i / (2 * segments)
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                points[point * 2] = cx + dx * radius
                points[point * 2 + 1] = cy + dy * radius
                outer?.let {
                    val feather = framebufferPixelAlong(dx, dy, pose)
                    it[point * 2] = cx + dx * (radius + feather)
                    it[point * 2 + 1] = cy + dy * (radius + feather)
                }
                point++
            }
        }
    }

    private fun framebufferPixelAlong(dx: Float, dy: Float, pose: Matrix3x2f): Float {
        val tx = pose.m00() * dx + pose.m10() * dy
        val ty = pose.m01() * dx + pose.m11() * dy
        val length = mc.window.guiScale * hypot(tx.toDouble(), ty.toDouble())
        return if (length > 1e-6) (1.0 / length).toFloat() else 0f
    }

    private fun localPixel(pose: Matrix3x2f): Float = max(
        max(framebufferPixelAlong(1f, 0f, pose), framebufferPixelAlong(0f, 1f, pose)),
        max(framebufferPixelAlong(0.70710677f, 0.70710677f, pose), framebufferPixelAlong(0.70710677f, -0.70710677f, pose)),
    )

    private fun emitGradientQuad(x: Float, y: Float, w: Float, h: Float, a: Int, b: Int, gradient: Gradient) =
        emitQuad(
            x, y, w, h,
            gradientColor(x, y, x, y, w, h, a, b, gradient),
            gradientColor(x, y + h, x, y, w, h, a, b, gradient),
            gradientColor(x + w, y + h, x, y, w, h, a, b, gradient),
            gradientColor(x + w, y, x, y, w, h, a, b, gradient),
        )

    private fun emitQuad(
        x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int = c0, c2: Int = c0, c3: Int = c0,
        textured: Boolean = false, setup: TextureSetup = TextureSetup.noTexture(),
    ) {
        val ctx = context ?: return
        if (w <= 0f || h <= 0f) return
        val pose = Matrix3x2f(ctx.pose())
        val bounds = bounds(x, y, x + w, y + h, pose)
        (ctx as GuiGraphicsExtractorAccessor).ctjsGuiRenderState.addGuiElement(
            QuadState(x, y, w, h, c0, c1, c2, c3, pose, textured, setup, clip?.screen, bounds),
        )
    }

    private fun emitQuadPoints(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int,
    ) {
        val ctx = context ?: return
        val pose = Matrix3x2f(ctx.pose())
        val vertices = acquireFloats(20)
        var count = 0
        count = put(vertices, count, x0, y0, color); count = put(vertices, count, x1, y1, color)
        count = put(vertices, count, x2, y2, color); count = put(vertices, count, x3, y3, color)
        emitMesh(vertices, count, false, TextureSetup.noTexture(), min(min(x0, x1), min(x2, x3)), min(min(y0, y1), min(y2, y3)), max(max(x0, x1), max(x2, x3)), max(max(y0, y1), max(y2, y3)), pose)
    }

    private fun put(
        vertices: FloatArray, index: Int, x: Float, y: Float, color: Int,
        boundsX: Float = 0f, boundsY: Float = 0f, boundsW: Float = 1f, boundsH: Float = 1f, textured: Boolean = false,
    ): Int {
        val offset = index * 5
        vertices[offset] = x; vertices[offset + 1] = y
        vertices[offset + 2] = if (textured) (x - boundsX) / boundsW else 0f
        vertices[offset + 3] = if (textured) (y - boundsY) / boundsH else 0f
        vertices[offset + 4] = Float.fromBits(color)
        return index + 1
    }

    private fun acquireFloats(size: Int): FloatArray = synchronized(vertexPool) {
        vertexPool.firstOrNull { it.size >= size }?.also(vertexPool::remove) ?: FloatArray(size)
    }

    private fun releaseFloats(vertices: FloatArray) = synchronized(vertexPool) { vertexPool.addLast(vertices) }

    private fun emitMesh(
        vertices: FloatArray, count: Int, textured: Boolean, setup: TextureSetup,
        minX: Float, minY: Float, maxX: Float, maxY: Float, pose: Matrix3x2f,
    ) {
        val ctx = context ?: return releaseFloats(vertices)
        val area = bounds(minX, minY, maxX, maxY, pose)
        synchronized(vertexPool) { inFlightVertices.addLast(vertices) }
        (ctx as GuiGraphicsExtractorAccessor).ctjsGuiRenderState.addGuiElement(MeshState(vertices, count, pose, textured, setup, clip?.screen, area))
    }

    private fun bounds(minX: Float, minY: Float, maxX: Float, maxY: Float, pose: Matrix3x2f): ScreenRectangle {
        var area = ScreenRectangle(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX - floor(minX)).toInt(), ceil(maxY - floor(minY)).toInt()).transformMaxBounds(pose)
        clip?.screen?.let { area = area.intersection(it) ?: ScreenRectangle.empty() }
        return area
    }

    private fun resolveGradient(direction: Any): Gradient {
        val name = direction.toString()
        return when {
            name.contains("TopToBottom") -> Gradient.TOP_TO_BOTTOM
            name.contains("TopLeftToBottomRight") -> Gradient.TL_TO_BR
            name.contains("BottomLeftToTopRight") -> Gradient.BL_TO_TR
            else -> Gradient.LEFT_TO_RIGHT
        }
    }

    private fun sampleColor(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float, a: Int, b: Int, gradient: Gradient?) =
        if (gradient == null) a else gradientColor(px, py, x, y, w, h, a, b, gradient)

    private fun gradientColor(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float, a: Int, b: Int, direction: Gradient): Int {
        val value = when (direction) {
            Gradient.TOP_TO_BOTTOM -> (py - y) / h
            Gradient.TL_TO_BR -> ((px - x) / w + (py - y) / h) / 2f
            Gradient.BL_TO_TR -> ((px - x) / w + 1f - (py - y) / h) / 2f
            Gradient.LEFT_TO_RIGHT -> (px - x) / w
        }.coerceIn(0f, 1f)
        fun channel(shift: Int) = (((a ushr shift) and 255) + (((b ushr shift) and 255) - ((a ushr shift) and 255)) * value).roundToInt()
        return applyAlpha((channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
    }

    private fun applyAlpha(color: Int): Int = withAlpha(color, (((color ushr 24) and 255) * alpha).roundToInt())
    private fun withAlpha(color: Int, value: Int) = (color and 0xffffff) or (value.coerceIn(0, 255) shl 24)
}
