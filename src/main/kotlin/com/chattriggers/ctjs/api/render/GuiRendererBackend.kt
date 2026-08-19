package com.chattriggers.ctjs.api.render

import com.chattriggers.ctjs.api.client.screenCompat
import com.chattriggers.ctjs.api.render.skia.SkijaPIP
import io.github.humbleui.skija.*
import io.github.humbleui.skija.Image as SkijaImage
import io.github.humbleui.skija.svg.SVGDOM
import io.github.humbleui.skija.svg.SVGLengthContext
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.max
import kotlin.math.roundToInt

/** Skija-backed 2D renderer used by the JavaScript-facing [Render2D] API. */
open class GuiRendererBackend {
    private val callbacks = CopyOnWriteArrayList<Runnable>()
    private val preCallbacks = CopyOnWriteArrayList<Runnable>()
    private val images = ConcurrentHashMap<String, CachedImage>()
    private val gifs = HashMap<String, CachedGif>()
    private val urlImages = HashMap<String, SkijaImage>()
    private val pendingUrls = ConcurrentHashMap.newKeySet<String>()
    private val failedUrls = ConcurrentHashMap.newKeySet<String>()
    private val downloadedUrls = ConcurrentLinkedQueue<Pair<String, ByteArray?>>()
    private val typefaces = HashMap<Font, Typeface>()
    private val fonts = HashMap<FontKey, io.github.humbleui.skija.Font>()
    private val alphaStack = ArrayDeque<Float>()
    private val scissorDepths = ArrayDeque<Int>()
    private var canvas: Canvas? = null
    private var checkerImage: SkijaImage? = null
    private val checkerShaders = HashMap<Float, Shader>()
    private val gradientShaders = HashMap<GradientKey, Shader>()
    private val hueColors = IntArray(7) { 0xff000000.toInt() or (Color.HSBtoRGB(it / 6f, 1f, 1f) and 0xffffff) }
    private var alpha = 1f
    private var saveDepth = 0

    @JvmField
    val defaultFont = Font("Default", "/assets/v5/font.otf")

    @JvmField val ALIGN_CENTER = 2
    @JvmField val ALIGN_MIDDLE = 16

    data class GifData(val width: Int, val height: Int, val frameCount: Int, val delays: IntArray) {
        override fun equals(other: Any?) = other is GifData && width == other.width && height == other.height &&
            frameCount == other.frameCount && delays.contentEquals(other.delays)
        override fun hashCode() = 31 * (31 * (31 * width + height) + frameCount) + delays.contentHashCode()
    }

    private data class CachedImage(val image: SkijaImage, var refs: Int = 1)
    private data class CachedGif(val frames: List<SkijaImage>, val delays: IntArray, val width: Int, val height: Int, var refs: Int = 1)
    private data class FontKey(val font: Font, val size: Float)
    private data class GradientKey(val x: Float, val y: Float, val width: Float, val height: Float, val color1: Int, val color2: Int, val direction: Gradient, val alpha: Float)
    private enum class Gradient { LEFT_TO_RIGHT, TOP_TO_BOTTOM, TL_TO_BR, BL_TO_TR }

    fun registerV5Render(callback: Runnable) = callback.also(callbacks::add)
    fun unregisterV5Render(callback: Runnable) { callbacks -= callback }
    fun registerV5PreRender(callback: Runnable) = callback.also(preCallbacks::add)
    fun unregisterV5PreRender(callback: Runnable) { preCallbacks -= callback }
    fun clearCallbacks() { callbacks.clear(); preCallbacks.clear() }

    fun runPreDrawables(context: GuiGraphicsExtractor) {
        if (preCallbacks.isNotEmpty()) SkijaPIP.draw(context, Runnable { runCallbacks(preCallbacks) }, pre = true)
    }

    fun runDrawables(context: GuiGraphicsExtractor) {
        if (callbacks.isEmpty()) return
        if (Minecraft.getInstance().screenCompat is Gui) context.blurBeforeThisStratum()
        SkijaPIP.draw(context, Runnable { runCallbacks(callbacks) })
    }

    private fun runCallbacks(list: List<Runnable>) = list.forEach {
        try { it.run() } catch (error: Exception) { error.printStackTrace() }
    }

    internal fun beginSkijaFrame(canvas: Canvas) {
        this.canvas = canvas
        alpha = 1f
        alphaStack.clear()
        scissorDepths.clear()
        saveDepth = 0
        processDownloads()
    }

    internal fun endSkijaFrame() {
        canvas?.restoreToCount(1)
        canvas = null
        alpha = 1f
        alphaStack.clear()
        scissorDepths.clear()
        saveDepth = 0
    }

    internal fun translateSkija(x: Float, y: Float) = canvas?.translate(x, y) != null
    internal fun scaleSkija(x: Float, y: Float) = canvas?.scale(x, y) != null
    internal fun rotateSkija(degrees: Float) = canvas?.rotate(degrees) != null

    fun blurBackground() = Unit
    fun save() {
        canvas?.save() ?: return
        alphaStack.addLast(alpha)
        saveDepth++
    }
    fun restore() {
        val active = canvas ?: return
        while (scissorDepths.lastOrNull() == saveDepth) {
            active.restore()
            scissorDepths.removeLast()
        }
        if (alphaStack.isNotEmpty()) {
            active.restore()
            alpha = alphaStack.removeLast()
            saveDepth--
        }
    }
    fun globalAlpha(value: Float) { alpha = value.coerceIn(0f, 1f) }
    fun scissor(x: Float, y: Float, width: Float, height: Float) {
        val active = canvas ?: return
        active.save()
        active.clipRect(Rect.makeXYWH(x, y, width, height), true)
        scissorDepths.addLast(saveDepth)
    }
    fun resetScissor() {
        val active = canvas ?: return
        while (scissorDepths.lastOrNull() == saveDepth) {
            active.restore()
            scissorDepths.removeLast()
        }
    }
    fun pushScissor(x: Float, y: Float, width: Float, height: Float) { save(); canvas?.clipRect(Rect.makeXYWH(x, y, width, height), true) }
    fun popScissor() = restore()

    fun drawRect(x: Float, y: Float, width: Float, height: Float, color: Int) = paint(color).use {
        canvas?.drawRect(Rect.makeXYWH(x, y, width, height), it)
        Unit
    }

    fun drawRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) = paint(color).use {
        canvas?.drawRRect(RRect.makeXYWH(x, y, width, height, radius.coerceAtLeast(0f)), it)
        Unit
    }

    fun drawRoundedRectVaried(x: Float, y: Float, width: Float, height: Float, color: Int, tl: Float, tr: Float, br: Float, bl: Float) =
        paint(color).use {
            canvas?.drawRRect(RRect.makeComplexXYWH(x, y, width, height, radii(tl, tr, br, bl)), it)
            Unit
        }

    fun drawCircle(x: Float, y: Float, radius: Float, color: Int) = paint(color).use { canvas?.drawCircle(x, y, radius, it); Unit }

    @JvmOverloads
    fun drawHollowRect(x: Float, y: Float, width: Float, height: Float, thickness: Float, color: Int, radius: Float = 0f) =
        paint(color, PaintMode.STROKE).use {
            it.strokeWidth = thickness
            if (radius > 0f) canvas?.drawRRect(RRect.makeXYWH(x, y, width, height, radius), it)
            else canvas?.drawRect(Rect.makeXYWH(x, y, width, height), it)
            Unit
        }

    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) = paint(color, PaintMode.STROKE).use {
        it.strokeWidth = thickness
        canvas?.drawLine(x1, y1, x2, y2, it)
        Unit
    }

    fun drawDropShadow(x: Float, y: Float, width: Float, height: Float, radius: Float, blur: Float, spread: Float, color: Int) =
        MaskFilter.makeBlur(FilterBlurMode.NORMAL, blur / 2f).use { filter ->
            paint(color).setMaskFilter(filter).use {
                canvas?.drawRRect(RRect.makeXYWH(x - spread, y - spread, width + spread * 2, height + spread * 2, radius + spread), it)
                Unit
            }
        }

    @JvmOverloads
    fun drawGradientRect(x: Float, y: Float, width: Float, height: Float, color1: Int, color2: Int, direction: Any, radius: Float = 0f) =
        gradientShader(x, y, width, height, color1, color2, direction).let { shader ->
            Paint().setAntiAlias(true).setShader(shader).use {
                if (radius > 0f) canvas?.drawRRect(RRect.makeXYWH(x, y, width, height, radius), it)
                else canvas?.drawRect(Rect.makeXYWH(x, y, width, height), it)
                Unit
            }
        }

    @JvmOverloads
    fun drawHollowGradientRect(x: Float, y: Float, width: Float, height: Float, thickness: Float, color1: Int, color2: Int, direction: Any, radius: Float = 0f) =
        gradientShader(x, y, width, height, color1, color2, direction).let { shader ->
            Paint().setAntiAlias(true).setShader(shader).setMode(PaintMode.STROKE).setStrokeWidth(thickness).use {
                if (radius > 0f) canvas?.drawRRect(RRect.makeXYWH(x, y, width, height, radius), it)
                else canvas?.drawRect(Rect.makeXYWH(x, y, width, height), it)
                Unit
            }
        }

    @JvmOverloads
    fun drawCheckerboard(x: Float, y: Float, width: Float, height: Float, radius: Float, size: Float = 4f) {
        val active = canvas ?: return
        val step = size.coerceAtLeast(0.1f)
        active.save()
        active.clipRRect(RRect.makeXYWH(x, y, width, height, radius), true)
        checkerShader(step).let { shader ->
            Paint().setShader(shader).setAlphaf(alpha).use {
                active.drawRect(Rect.makeXYWH(x, y, width, height), it)
            }
        }
        active.restore()
    }

    fun drawHueBar(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        val colors = if (alpha == 1f) hueColors else IntArray(7) { applyAlpha(hueColors[it]) }
        Shader.makeLinearGradient(x, y, x + width, y, colors).use { shader ->
            Paint().setAntiAlias(true).setShader(shader).use { paint ->
                canvas?.drawRRect(RRect.makeXYWH(x, y, width, height, radius), paint)
            }
        }
    }

    fun getDefaultFont() = defaultFont

    @JvmOverloads
    fun text(text: String, x: Float, y: Float, size: Float, color: Int, font: Font? = defaultFont, align: Int) {
        val skijaFont = skijaFont(font ?: defaultFont, size)
        val metrics = skijaFont.metrics
        val baseline = when {
            align and 32 != 0 -> y - metrics.descent
            align and 16 != 0 -> y - (metrics.ascent + metrics.descent) / 2f
            align and 8 != 0 -> y - metrics.ascent
            else -> y
        }
        if (align and 6 == 0) {
            paint(color).use { canvas?.drawString(text, x, baseline, skijaFont, it) }
            return
        }
        TextLine.make(text, skijaFont).use { line ->
            val drawX = when {
                align and 4 != 0 -> x - line.width
                align and 2 != 0 -> x - line.width / 2f
                else -> x
            }
            paint(color).use { canvas?.drawTextLine(line, drawX, baseline, it) }
        }
    }

    @JvmOverloads
    fun textWidth(text: String, size: Float, font: Font? = defaultFont) =
        skijaFont(font ?: defaultFont, size).measureTextWidth(text)

    fun loadImage(path: String): String {
        if (path.isUrl()) return path
        synchronized(images) {
            images[path]?.let { it.refs++; return path }
            images[path] = CachedImage(createImage(readImage(path), path))
        }
        return path
    }

    fun unloadImage(path: String) = synchronized(images) {
        images[path]?.let { if (--it.refs <= 0) { it.image.close(); images.remove(path) } }
    }

    fun isImageLoaded(path: String) = images.containsKey(path)

    @JvmOverloads
    fun drawImage(path: String, x: Float, y: Float, width: Float, height: Float, radius: Float = 0f, imageAlpha: Float = 1f) {
        if (path.isUrl()) return drawImageFromUrl(path, x, y, width, height, radius, imageAlpha)
        val image = images[path] ?: runCatching { loadImage(path) }.getOrNull()?.let(images::get) ?: return
        drawImage(image.image, x, y, width, height, radius, imageAlpha)
    }

    @JvmOverloads
    fun drawImageFromUrl(url: String, x: Float, y: Float, width: Float, height: Float, radius: Float = 0f, imageAlpha: Float = 1f) {
        if (url == "none" || url in failedUrls) return
        urlImages[url]?.let { return drawImage(it, x, y, width, height, radius, imageAlpha) }
        if (pendingUrls.add(url)) Thread({
            val bytes = runCatching {
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000; setRequestProperty("User-Agent", "V5-Loader")
                }.getInputStream().use { it.readBytes() }
            }.getOrNull()
            downloadedUrls += url to bytes
        }, "V5 image ${url.hashCode()}").apply { isDaemon = true }.start()
    }

    fun loadGif(path: String): GifData? {
        synchronized(gifs) {
            gifs[path]?.let { it.refs++; return GifData(it.width, it.height, it.frames.size, it.delays) }
        }
        val decoded = decodeGif(path) ?: return null
        synchronized(gifs) { gifs[path] = decoded }
        return GifData(decoded.width, decoded.height, decoded.frames.size, decoded.delays)
    }

    fun unloadGif(path: String) = synchronized(gifs) {
        gifs[path]?.let { if (--it.refs <= 0) { it.frames.forEach(SkijaImage::close); gifs.remove(path) } }
    }

    @JvmOverloads
    fun drawGif(path: String, x: Float, y: Float, width: Float, height: Float, frameIndex: Int, radius: Float = 0f, imageAlpha: Float = 1f) {
        val frames = synchronized(gifs) { gifs[path]?.frames } ?: return
        if (frames.isNotEmpty()) drawImage(frames[Math.floorMod(frameIndex, frames.size)], x, y, width, height, radius, imageAlpha)
    }

    fun clearImageCache() {
        synchronized(images) { images.values.forEach { it.image.close() }; images.clear() }
        synchronized(gifs) { gifs.values.flatMap { it.frames }.forEach(SkijaImage::close); gifs.clear() }
        urlImages.values.forEach(SkijaImage::close); urlImages.clear(); pendingUrls.clear(); failedUrls.clear(); downloadedUrls.clear()
    }

    fun getCacheStats() = "Images: ${images.size}, GIFs: ${gifs.size}, URLs: ${urlImages.size}"

    fun destroy() {
        clearImageCache()
        checkerImage?.close(); checkerImage = null
        checkerShaders.values.forEach(Shader::close); checkerShaders.clear()
        gradientShaders.values.forEach(Shader::close); gradientShaders.clear()
        fonts.values.forEach(io.github.humbleui.skija.Font::close); fonts.clear()
        typefaces.values.forEach(Typeface::close); typefaces.clear()
    }

    private fun drawImage(image: SkijaImage, x: Float, y: Float, width: Float, height: Float, radius: Float, imageAlpha: Float) {
        Paint().setAntiAlias(true).setAlphaf(alpha * imageAlpha.coerceIn(0f, 1f)).use { paint ->
            val active = canvas ?: return
            if (radius > 0f) {
                active.save()
                active.clipRRect(RRect.makeXYWH(x, y, width, height, radius), true)
            }
            active.drawImageRect(
                image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeXYWH(x, y, width, height),
                SamplingMode.LINEAR, paint, true,
            )
            if (radius > 0f) active.restore()
        }
    }

    private fun checkerImage() = checkerImage ?: Surface.makeRaster(
        ImageInfo(2, 2, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.getSRGB()),
    ).use { surface ->
        Paint().setColor(0xff404040.toInt()).use { surface.canvas.drawRect(Rect.makeWH(2f, 2f), it) }
        Paint().setColor(0xff737373.toInt()).use {
            surface.canvas.drawRect(Rect.makeXYWH(1f, 0f, 1f, 1f), it)
            surface.canvas.drawRect(Rect.makeXYWH(0f, 1f, 1f, 1f), it)
        }
        surface.makeImageSnapshot().also { checkerImage = it }
    }

    private fun checkerShader(size: Float) = checkerShaders.getOrPut(size) {
        checkerImage().makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, Matrix33.makeScale(size))
    }

    private fun processDownloads() {
        repeat(5) {
            val (url, bytes) = downloadedUrls.poll() ?: return
            if (bytes == null) failedUrls += url else runCatching { createImage(bytes, url) }
                .onSuccess { urlImages[url] = it }.onFailure { failedUrls += url }
            pendingUrls -= url
        }
    }

    private fun createImage(bytes: ByteArray, name: String): SkijaImage {
        if (!name.substringBefore('?').endsWith(".svg", true)) return SkijaImage.makeDeferredFromEncodedBytes(bytes)
        Data.makeFromBytes(bytes).use { data ->
            SVGDOM(data).use { dom ->
                val root = dom.root ?: error("Invalid SVG: $name")
                val size = root.getIntrinsicSize(SVGLengthContext(256f, 256f, 96f))
                val width = max(1, size.x.roundToInt())
                val height = max(1, size.y.roundToInt())
                Surface.makeRaster(ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.getSRGB())).use { surface ->
                    surface.canvas.clear(0)
                    dom.setContainerSize(width.toFloat(), height.toFloat())
                    dom.render(surface.canvas)
                    return surface.makeImageSnapshot()
                }
            }
        }
    }

    private fun decodeGif(path: String): CachedGif? = runCatching {
        FileInputStream(path).use { stream ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            reader.input = ImageIO.createImageInputStream(stream)
            val count = reader.getNumImages(true)
            val first = reader.read(0)
            val master = BufferedImage(first.width, first.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = master.createGraphics().apply { background = Color(0, 0, 0, 0) }
            val frames = ArrayList<SkijaImage>(count)
            val delays = IntArray(count)
            repeat(count) { index ->
                val frame = reader.read(index)
                val tree = reader.getImageMetadata(index).getAsTree(reader.getImageMetadata(index).nativeMetadataFormatName) as IIOMetadataNode
                val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                val descriptor = tree.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                val x = descriptor.getAttribute("imageLeftPosition").toInt()
                val y = descriptor.getAttribute("imageTopPosition").toInt()
                delays[index] = (control.getAttribute("delayTime").toInt() * 10).coerceAtLeast(10)
                graphics.drawImage(frame, x, y, null)
                frames += bufferedImage(master)
                if (control.getAttribute("disposalMethod") == "restoreToBackgroundColor") graphics.clearRect(x, y, frame.width, frame.height)
            }
            graphics.dispose(); reader.dispose()
            CachedGif(frames, delays, first.width, first.height)
        }
    }.onFailure { println("[V5] Failed to load GIF $path: ${it.message}") }.getOrNull()

    private fun bufferedImage(image: BufferedImage): SkijaImage {
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return SkijaImage.makeDeferredFromEncodedBytes(output.toByteArray())
    }

    private fun skijaFont(font: Font, size: Float) = fonts.getOrPut(FontKey(font, size)) {
        io.github.humbleui.skija.Font(typefaces.getOrPut(font) {
            FontMgr.getDefault().makeFromData(Data.makeFromBytes(font.buffer().let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }))
                ?: error("Failed to load font ${font.name}")
        }, size)
            .setSubpixel(true)
            .setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS)
            .setHinting(FontHinting.SLIGHT)
    }

    private fun paint(color: Int, mode: PaintMode = PaintMode.FILL) = Paint()
        .setAntiAlias(true).setMode(mode).setColor(applyAlpha(color))

    private fun gradientShader(x: Float, y: Float, width: Float, height: Float, color1: Int, color2: Int, direction: Any): Shader {
        val resolved = resolveGradient(direction)
        val key = GradientKey(x, y, width, height, color1, color2, resolved, alpha)
        gradientShaders[key]?.let { return it }
        val (endX, endY) = when (resolved) {
            Gradient.TOP_TO_BOTTOM -> x to y + height
            Gradient.TL_TO_BR -> x + width to y + height
            Gradient.BL_TO_TR -> x + width to y - height
            Gradient.LEFT_TO_RIGHT -> x + width to y
        }
        return Shader.makeLinearGradient(x, y, endX, endY, intArrayOf(applyAlpha(color1), applyAlpha(color2))).also { gradientShaders[key] = it }
    }

    private fun resolveGradient(direction: Any) = when {
        direction.toString().contains("TopToBottom") -> Gradient.TOP_TO_BOTTOM
        direction.toString().contains("TopLeftToBottomRight") -> Gradient.TL_TO_BR
        direction.toString().contains("BottomLeftToTopRight") -> Gradient.BL_TO_TR
        else -> Gradient.LEFT_TO_RIGHT
    }

    private fun radii(tl: Float, tr: Float, br: Float, bl: Float) = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
    private fun applyAlpha(color: Int) = (color and 0xffffff) or ((((color ushr 24) and 255) * alpha).roundToInt().coerceIn(0, 255) shl 24)
    private fun String.isUrl() = startsWith("http://", true) || startsWith("https://", true)
    private fun readImage(path: String): ByteArray = when {
        File(path).isFile -> FileInputStream(path)
        else -> GuiRendererBackend::class.java.getResourceAsStream(path) ?: throw FileNotFoundException(path)
    }.use { it.readBytes() }
}
