package com.v5.render

import com.v5.render.helper.Image
import com.v5.render.helper.Font
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.v5.render.helper.TextureTracker
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.GlBackend
import net.minecraft.client.texture.GlTexture
import net.minecraft.util.Identifier
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.*
import org.lwjgl.nanovg.NanoSVG.*
import org.lwjgl.opengl.GL30
import org.lwjgl.stb.STBImage.stbi_load_from_memory
import org.lwjgl.stb.STBImage.stbi_image_free
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.FileInputStream

object NVGRenderer {

    private val mc = MinecraftClient.getInstance()
    private val nvgColor = NVGColor.malloc()
    private val nvgColor2 = NVGColor.malloc()
    private val nvgPaint = NVGPaint.malloc()

    private var checkTexId = 0
    private var hueTexId = 0

    val defaultFont by lazy {
        try {
            Font("Default", mc.resourceManager.getResource(Identifier.of("v5", "font.otf")).get().inputStream)
        } catch (e: Exception) {
            println("[V5] Failed to load font: ${e.message}")
            null
        }
    }

    private val fontMap = HashMap<Font, NVGFont>()
    private val fontBounds = FloatArray(4)

    private const val MAX_CACHE_SIZE_BYTES = 100L * 1024 * 1024 // 100MB
    private var currentCacheSize = 0L

    private val imageCache = object : LinkedHashMap<String, CachedImage>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedImage>): Boolean {
            if (currentCacheSize > MAX_CACHE_SIZE_BYTES && size > 1) {
                val entry = eldest.value
                if (entry.refCount <= 0) {
                    currentCacheSize -= entry.estimatedSize
                    if (vg != -1L) nvgDeleteImage(vg, entry.nvgId)
                    return true
                }
            }
            return false
        }
    }

    private val gifCache = ConcurrentHashMap<String, CachedGif>()
    private val glTextureCache = ConcurrentHashMap<Int, Int>()

    private val urlCache = ConcurrentHashMap<String, Int>()
    private val pendingDownloads = ConcurrentHashMap.newKeySet<String>()

    private data class DecodedImage(
        val url: String,
        val pixels: ByteBuffer?,
        val width: Int,
        val height: Int
    )
    private val downloadQueue = java.util.concurrent.LinkedBlockingQueue<DecodedImage>()
    private const val MAX_DOWNLOADS_PER_FRAME = 5

    private val renderCallbacks = CopyOnWriteArrayList<Runnable>()

    private var drawing = false
    @JvmField var vg = -1L

    data class GifData(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val delays: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GifData) return false
            return width == other.width && height == other.height &&
                    frameCount == other.frameCount && delays.contentEquals(other.delays)
        }
        override fun hashCode(): Int = 31 * (31 * (31 * width + height) + frameCount) + delays.contentHashCode()
    }

    private data class CachedGif(
        var frameIds: IntArray?,
        var rawFrames: ArrayList<ByteBuffer>?,
        val delays: IntArray,
        val width: Int,
        val height: Int,
        var refCount: Int
    )

    private data class CachedImage(
        val nvgId: Int,
        var refCount: Int,
        val estimatedSize: Long = 0L
    )

    private data class NVGFont(val id: Int, val buffer: ByteBuffer)

    @JvmStatic
    fun registerV5Render(runnable: Runnable) {
        renderCallbacks.add(runnable)
    }

    @JvmStatic
    fun unregisterV5Render(runnable: Runnable) {
        renderCallbacks.remove(runnable)
    }

    @JvmStatic
    fun runDrawables() {
        processDownloadQueue()

        renderCallbacks.forEach { runnable ->
            try {
                runnable.run()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isGameReady(): Boolean {
        return mc.window != null && mc.window.handle != 0L
    }

    private fun ensureInitialized() {
        if (vg != -1L) return
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        if (vg == -1L) throw RuntimeException("Failed to initialize NanoVG")
    }

    @JvmStatic
    fun beginFrame(width: Float, height: Float) {
        if (!isGameReady()) return
        ensureInitialized()
        if (drawing) return

        val framebuffer = mc.framebuffer
        val glFramebuffer = (framebuffer.colorAttachment as GlTexture).getOrCreateFramebuffer(
            (RenderSystem.getDevice() as GlBackend).bufferManager,
            null
        )

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, glFramebuffer)
        GlStateManager._viewport(0, 0, framebuffer.textureWidth, framebuffer.textureHeight)
        GlStateManager._activeTexture(GL30.GL_TEXTURE0)

        nvgBeginFrame(vg, width, height, 1f)
        nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
        drawing = true
    }

    @JvmStatic
    fun endFrame() {
        if (!drawing || vg == -1L) return

        nvgEndFrame(vg)
        GlStateManager._disableCull()
        GlStateManager._disableDepthTest()
        GlStateManager._enableBlend()
        GlStateManager._blendFuncSeparate(770, 771, 1, 0)
        GlStateManager._glUseProgram(0)

        if (TextureTracker.prevActiveTexture != -1) {
            GlStateManager._activeTexture(TextureTracker.prevActiveTexture)
            if (TextureTracker.prevBoundTexture != -1) GlStateManager._bindTexture(TextureTracker.prevBoundTexture)
        }

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        drawing = false
    }

    private fun applyColor(color: Int, target: NVGColor = nvgColor) {
        nvgRGBA(
            ((color shr 16) and 0xFF).toByte(),
            ((color shr 8) and 0xFF).toByte(),
            (color and 0xFF).toByte(),
            ((color shr 24) and 0xFF).toByte(),
            target
        )
    }

    private fun applyGradient(x: Float, y: Float, w: Float, h: Float, color1: Int, color2: Int, direction: Any) {
        applyColor(color1, nvgColor)
        applyColor(color2, nvgColor2)
        val dir = direction.toString()
        when {
            dir.contains("LeftToRight") -> nvgLinearGradient(vg, x, y, x + w, y, nvgColor, nvgColor2, nvgPaint)
            dir.contains("TopToBottom") -> nvgLinearGradient(vg, x, y, x, y + h, nvgColor, nvgColor2, nvgPaint)
            dir.contains("TopLeftToBottomRight") -> nvgLinearGradient(vg, x, y, x + w, y + h, nvgColor, nvgColor2, nvgPaint)
            dir.contains("BottomLeftToTopRight") -> nvgLinearGradient(vg, x, y + h, x + w, y, nvgColor, nvgColor2, nvgPaint)
            else -> nvgLinearGradient(vg, x, y, x + w, y, nvgColor, nvgColor2, nvgPaint)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawCheckerboard(x: Float, y: Float, w: Float, h: Float, radius: Float, size: Float = 4f) {
        if (!drawing) return

        if (checkTexId == 0) {
            val buf = MemoryUtil.memAlloc(16)
            val c1 = 64.toByte()
            val c2 = 115.toByte()
            val a = 255.toByte()
            buf.put(c1).put(c1).put(c1).put(a).put(c2).put(c2).put(c2).put(a)
            buf.put(c2).put(c2).put(c2).put(a).put(c1).put(c1).put(c1).put(a)
            buf.flip()
            checkTexId = nvgCreateImageRGBA(vg, 2, 2, NVG_IMAGE_REPEATX or NVG_IMAGE_REPEATY or NVG_IMAGE_NEAREST, buf)
            MemoryUtil.memFree(buf)
        }

        nvgImagePattern(vg, x, y, size * 2, size * 2, 0f, checkTexId, 1f, nvgPaint)
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    @JvmStatic
    fun drawHueBar(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        if (!drawing) return

        if (hueTexId == 0) {
            val width = 128
            val buf = MemoryUtil.memAlloc(width * 4)
            for (i in 0 until width) {
                val rgb = Color.HSBtoRGB(i.toFloat() / width, 1f, 1f)
                buf.put((rgb shr 16 and 0xFF).toByte())
                buf.put((rgb shr 8 and 0xFF).toByte())
                buf.put((rgb and 0xFF).toByte())
                buf.put(255.toByte())
            }
            buf.flip()
            hueTexId = nvgCreateImageRGBA(vg, width, 1, 0, buf)
            MemoryUtil.memFree(buf)
        }

        nvgImagePattern(vg, x, y, w, 1f, 0f, hueTexId, 1f, nvgPaint)
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    @JvmStatic
    @JvmOverloads
    fun drawGradientRect(x: Float, y: Float, w: Float, h: Float, color1: Int, color2: Int, direction: Any, radius: Float = 0f) {
        if (!drawing) return
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        applyGradient(x, y, w, h, color1, color2, direction)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    @JvmStatic
    @JvmOverloads
    fun drawHollowGradientRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color1: Int, color2: Int, direction: Any, radius: Float = 0f) {
        if (!drawing) return
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        nvgStrokeWidth(vg, thickness)
        applyGradient(x, y, w, h, color1, color2, direction)
        nvgStrokePaint(vg, nvgPaint)
        nvgStroke(vg)
    }

    @JvmStatic
    fun linearGradient(sx: Float, sy: Float, ex: Float, ey: Float, color1: Int, color2: Int) {
        applyColor(color1, nvgColor)
        applyColor(color2, nvgColor2)
        nvgLinearGradient(vg, sx, sy, ex, ey, nvgColor, nvgColor2, nvgPaint)
    }

    @JvmStatic
    fun setGlobalCompositeOperation(op: Int) {
        if (vg != -1L) nvgGlobalCompositeOperation(vg, op)
    }

    @JvmStatic fun save() { if (vg != -1L) nvgSave(vg) }
    @JvmStatic fun restore() { if (vg != -1L) nvgRestore(vg) }
    @JvmStatic fun translate(x: Float, y: Float) { if (vg != -1L) nvgTranslate(vg, x, y) }
    @JvmStatic fun rotate(angle: Float) { if (vg != -1L) nvgRotate(vg, Math.toRadians(angle.toDouble()).toFloat()) }
    @JvmStatic fun scale(x: Float, y: Float) { if (vg != -1L) nvgScale(vg, x, y) }
    @JvmStatic fun globalAlpha(alpha: Float) { if (vg != -1L) nvgGlobalAlpha(vg, alpha.coerceIn(0f, 1f)) }

    @JvmStatic
    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (!drawing) return
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        applyColor(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    @JvmStatic
    fun drawRoundedRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        if (!drawing) return
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        applyColor(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    @JvmStatic
    fun drawRoundedRectVaried(x: Float, y: Float, w: Float, h: Float, color: Int, tl: Float, tr: Float, br: Float, bl: Float) {
        if (!drawing) return
        nvgBeginPath(vg)
        nvgRoundedRectVarying(vg, x, y, w, h, tl, tr, br, bl)
        applyColor(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    @JvmStatic
    fun drawCircle(x: Float, y: Float, radius: Float, color: Int) {
        if (!drawing) return
        nvgBeginPath(vg)
        nvgCircle(vg, x, y, radius)
        applyColor(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    @JvmStatic
    fun drawDropShadow(x: Float, y: Float, w: Float, h: Float, radius: Float, blur: Float, spread: Float, color: Int) {
        if (!drawing) return
        applyColor(color, nvgColor)
        applyColor(0, nvgColor2)

        nvgBoxGradient(vg, x - spread, y - spread, w + 2 * spread, h + 2 * spread, radius + spread, blur, nvgColor, nvgColor2, nvgPaint)

        nvgBeginPath(vg)
        nvgRect(vg, x - spread - blur, y - spread - blur, w + 2 * spread + 2 * blur, h + 2 * spread + 2 * blur)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    @JvmStatic
    @JvmOverloads
    fun drawHollowRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color: Int, radius: Float = 0f) {
        if (!drawing) return
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        nvgStrokeWidth(vg, thickness)
        nvgPathWinding(vg, NVG_HOLE)
        applyColor(color)
        nvgStrokeColor(vg, nvgColor)
        nvgStroke(vg)
    }

    @JvmStatic
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        if (!drawing) return
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1, y1)
        nvgLineTo(vg, x2, y2)
        nvgStrokeWidth(vg, thickness)
        applyColor(color)
        nvgStrokeColor(vg, nvgColor)
        nvgStroke(vg)
    }

    @JvmStatic
    fun loadImage(path: String): String {
        ensureInitialized()

        synchronized(imageCache) {
            imageCache[path]?.let {
                it.refCount++
                return path
            }
        }

        val image = Image.fromPath(path)
        val nvgId = if (image.isSVG) loadSVGImage(image) else loadRasterImage(image)

        val estimatedSize = estimateImageSize(image)

        synchronized(imageCache) {
            imageCache[path] = CachedImage(nvgId, 1, estimatedSize)
            currentCacheSize += estimatedSize
        }

        return path
    }

    @JvmStatic
    fun unloadImage(path: String) {
        synchronized(imageCache) {
            val cached = imageCache[path] ?: return
            if (--cached.refCount <= 0) {
                nvgDeleteImage(vg, cached.nvgId)
                currentCacheSize -= cached.estimatedSize
                imageCache.remove(path)
            }
        }
    }

    @JvmStatic
    fun isImageLoaded(path: String): Boolean = synchronized(imageCache) { imageCache.containsKey(path) }

    private fun estimateImageSize(image: Image): Long {
        return 512L * 512L * 4L
    }

    private fun loadRasterImage(image: Image): Int {
        val w = IntArray(1)
        val h = IntArray(1)
        val channels = IntArray(1)
        val pixels = stbi_load_from_memory(image.buffer(), w, h, channels, 4)
            ?: throw RuntimeException("Failed to load image: ${image.identifier}")
        val nvgId = nvgCreateImageRGBA(vg, w[0], h[0], 0, pixels)
        stbi_image_free(pixels)
        return nvgId
    }

    private fun loadSVGImage(image: Image): Int {
        val svgContent = image.buffer().let { buf ->
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            buf.rewind()
            String(bytes)
        }

        val svg = nsvgParse(svgContent, "px", 96f)
            ?: throw RuntimeException("Failed to parse SVG: ${image.identifier}")

        val width = svg.width().toInt()
        val height = svg.height().toInt()

        if (width <= 0 || height <= 0) {
            nsvgDelete(svg)
            throw RuntimeException("Invalid SVG dimensions: ${image.identifier}")
        }

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            val rasterizer = nsvgCreateRasterizer()
            nsvgRasterize(rasterizer, svg, 0f, 0f, 1f, buffer, width, height, width * 4)
            val nvgId = nvgCreateImageRGBA(vg, width, height, 0, buffer)
            nsvgDeleteRasterizer(rasterizer)
            return nvgId
        } finally {
            nsvgDelete(svg)
            MemoryUtil.memFree(buffer)
        }
    }

    @JvmStatic
    fun loadGif(path: String): GifData? {
        gifCache[path]?.let { cached ->
            synchronized(cached) { cached.refCount++ }
            val count = cached.frameIds?.size ?: cached.rawFrames?.size ?: 0
            return GifData(cached.width, cached.height, count, cached.delays)
        }

        val file = File(path)
        if (!file.exists()) return null

        try {
            FileInputStream(file).use { stream ->
                val readers = ImageIO.getImageReadersByFormatName("gif")
                if (!readers.hasNext()) return null
                val reader = readers.next()
                reader.input = ImageIO.createImageInputStream(stream)

                val numFrames = reader.getNumImages(true)
                val rawFrames = ArrayList<ByteBuffer>(numFrames)
                val delays = IntArray(numFrames)

                val firstFrame = reader.read(0)
                val width = firstFrame.width
                val height = firstFrame.height

                val masterImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val masterGraphics = masterImage.createGraphics()
                masterGraphics.background = Color(0, 0, 0, 0)

                for (i in 0 until numFrames) {
                    val frameImage = reader.read(i)
                    val metadata = reader.getImageMetadata(i)
                    val tree = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode

                    val gce = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                    delays[i] = (gce.getAttribute("delayTime").toInt() * 10).coerceAtLeast(10)
                    val disposal = gce.getAttribute("disposalMethod")

                    val imgDesc = tree.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                    val fx = imgDesc.getAttribute("imageLeftPosition").toInt()
                    val fy = imgDesc.getAttribute("imageTopPosition").toInt()

                    masterGraphics.drawImage(frameImage, fx, fy, null)
                    rawFrames.add(bufferedImageToByteBuffer(masterImage))

                    if (disposal == "restoreToBackgroundColor") {
                        masterGraphics.clearRect(fx, fy, frameImage.width, frameImage.height)
                    }
                }
                masterGraphics.dispose()
                reader.dispose()

                gifCache[path] = CachedGif(null, rawFrames, delays, width, height, 1)
                return GifData(width, height, numFrames, delays)
            }
        } catch (e: Exception) {
            println("[V5] Failed to load GIF $path: ${e.message}")
            return null
        }
    }

    @JvmStatic
    fun unloadGif(path: String) {
        val cached = gifCache[path] ?: return
        val shouldFree = synchronized(cached) { --cached.refCount <= 0 }

        if (shouldFree) {
            cached.frameIds?.forEach { nvgDeleteImage(vg, it) }
            cached.rawFrames?.forEach { MemoryUtil.memFree(it) }
            gifCache.remove(path)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawGif(path: String, x: Float, y: Float, w: Float, h: Float, frameIndex: Int, radius: Float = 0f, alpha: Float = 1f) {
        if (!drawing) return
        val cached = gifCache[path] ?: return

        if (cached.frameIds == null) {
            val raw = cached.rawFrames ?: return
            cached.frameIds = IntArray(raw.size) { i ->
                val id = nvgCreateImageRGBA(vg, cached.width, cached.height, 0, raw[i])
                MemoryUtil.memFree(raw[i])
                id
            }
            cached.rawFrames = null
        }

        val frames = cached.frameIds!!
        if (frames.isEmpty()) return

        nvgImagePattern(vg, x, y, w, h, 0f, frames[frameIndex % frames.size], alpha, nvgPaint)
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    private fun bufferedImageToByteBuffer(image: BufferedImage): ByteBuffer {
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)

        val buffer = MemoryUtil.memAlloc(pixels.size * 4)
        for (pixel in pixels) {
            buffer.put((pixel shr 16 and 0xFF).toByte()) // R
            buffer.put((pixel shr 8 and 0xFF).toByte())  // G
            buffer.put((pixel and 0xFF).toByte())        // B
            buffer.put((pixel shr 24 and 0xFF).toByte()) // A
        }
        buffer.flip()
        return buffer
    }

    @JvmStatic
    @JvmOverloads
    fun drawImage(path: String, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f) {
        if (!drawing) return

        val cached = synchronized(imageCache) { imageCache[path] }

        if (cached == null) {
            try {
                loadImage(path)
                drawImage(path, x, y, w, h, radius, alpha)
            } catch (e: Exception) {
                println("[V5] Failed to load image: $path - ${e.message}")
            }
            return
        }

        nvgImagePattern(vg, x, y, w, h, 0f, cached.nvgId, alpha, nvgPaint)
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    private fun processDownloadQueue() {
        var processed = 0
        while (processed < MAX_DOWNLOADS_PER_FRAME) {
            val decoded = downloadQueue.poll() ?: break

            if (decoded.pixels != null && vg != -1L) {
                val nvgId = nvgCreateImageRGBA(vg, decoded.width, decoded.height, 0, decoded.pixels)
                stbi_image_free(decoded.pixels)
                urlCache[decoded.url] = nvgId
            } else {
                urlCache[decoded.url] = 0
            }
            pendingDownloads.remove(decoded.url)
            processed++
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawImageFromUrl(url: String, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f) {
        if (!drawing || url == "none") return

        urlCache[url]?.let { cachedId ->
            if (cachedId > 0) {
                nvgImagePattern(vg, x, y, w, h, 0f, cachedId, alpha, nvgPaint)
                nvgBeginPath(vg)
                if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
                nvgFillPaint(vg, nvgPaint)
                nvgFill(vg)
            }
            return
        }

        if (pendingDownloads.add(url)) {
            Thread({
                try {
                    val connection = java.net.URL(url).openConnection().apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    val bytes = connection.getInputStream().use { it.readBytes() }

                    val buffer = MemoryUtil.memAlloc(bytes.size).put(bytes).flip() as ByteBuffer
                    val wArr = IntArray(1)
                    val hArr = IntArray(1)
                    val cArr = IntArray(1)

                    val pixels = stbi_load_from_memory(buffer, wArr, hArr, cArr, 4)
                    MemoryUtil.memFree(buffer)

                    if (pixels != null) {
                        downloadQueue.add(DecodedImage(url, pixels, wArr[0], hArr[0]))
                    } else {
                        downloadQueue.add(DecodedImage(url, null, 0, 0))
                    }
                } catch (e: Exception) {
                    downloadQueue.add(DecodedImage(url, null, 0, 0))
                }
            }, "V5-ImageDownload-${url.hashCode()}").start()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun drawGLTexture(glTextureId: Int, texW: Int, texH: Int, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f) {
        if (!drawing) return
        ensureInitialized()

        val nvgId = glTextureCache.getOrPut(glTextureId) {
            nvglCreateImageFromHandle(vg, glTextureId, texW, texH, NVG_IMAGE_NODELETE)
        }
        if (nvgId == 0) return

        nvgImagePattern(vg, x, y, w, h, 0f, nvgId, alpha, nvgPaint)
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    @JvmStatic
    @JvmOverloads
    fun drawGLTextureRegion(glTextureId: Int, textureWidth: Int, textureHeight: Int, subX: Int, subY: Int, subW: Int, subH: Int, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f) {
        if (!drawing) return
        ensureInitialized()

        val nvgId = glTextureCache.getOrPut(glTextureId) {
            nvglCreateImageFromHandle(vg, glTextureId, textureWidth, textureHeight, NVG_IMAGE_NODELETE)
        }
        if (nvgId == 0) return

        val sw = subW.toFloat() / textureWidth
        val sh = subH.toFloat() / textureHeight
        val iw = w / sw
        val ih = h / sh
        val ix = x - iw * (subX.toFloat() / textureWidth)
        val iy = y - ih * (subY.toFloat() / textureHeight)

        nvgImagePattern(vg, ix, iy, iw, ih, 0f, nvgId, alpha, nvgPaint)
        nvgBeginPath(vg)
        if (radius > 0) nvgRoundedRect(vg, x, y, w, h, radius) else nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    @JvmStatic
    fun invalidateGLTexture(glTextureId: Int) {
        glTextureCache.remove(glTextureId)?.takeIf { it != 0 }?.let { nvgDeleteImage(vg, it) }
    }

    @JvmStatic
    fun clearImageCache() {
        if (vg == -1L) return

        synchronized(imageCache) {
            imageCache.values.forEach { nvgDeleteImage(vg, it.nvgId) }
            imageCache.clear()
            currentCacheSize = 0L
        }

        gifCache.values.forEach { gif ->
            gif.frameIds?.forEach { nvgDeleteImage(vg, it) }
            gif.rawFrames?.forEach { MemoryUtil.memFree(it) }
        }
        gifCache.clear()

        glTextureCache.values.filter { it != 0 }.forEach { nvgDeleteImage(vg, it) }
        glTextureCache.clear()

        urlCache.values.filter { it > 0 }.forEach { nvgDeleteImage(vg, it) }
        urlCache.clear()
        pendingDownloads.clear()

        if (checkTexId != 0) {
            nvgDeleteImage(vg, checkTexId)
            checkTexId = 0
        }
        if (hueTexId != 0) {
            nvgDeleteImage(vg, hueTexId)
            hueTexId = 0
        }
    }

    @JvmStatic
    fun getCacheStats(): String {
        return "Images: ${imageCache.size}, GIFs: ${gifCache.size}, GL: ${glTextureCache.size}, URLs: ${urlCache.size}, Size: ${currentCacheSize / 1024}KB"
    }

    @JvmStatic
    fun scissor(x: Float, y: Float, w: Float, h: Float) {
        if (vg != -1L) nvgIntersectScissor(vg, x, y, w, h)
    }

    @JvmStatic
    fun pushScissor(x: Float, y: Float, w: Float, h: Float) {
        if (vg != -1L) {
            nvgSave(vg)
            nvgIntersectScissor(vg, x, y, w, h)
        }
    }

    @JvmStatic
    fun popScissor() {
        if (vg != -1L) nvgRestore(vg)
    }

    @JvmStatic
    fun resetScissor() {
        if (vg != -1L) nvgResetScissor(vg)
    }

    @JvmStatic
    @JvmOverloads
    fun text(text: String, x: Float, y: Float, size: Float, color: Int, font: Font? = defaultFont, align: Int) {
        if (font == null || !drawing) return
        nvgFontSize(vg, size)
        nvgFontFaceId(vg, getFontID(font))
        nvgTextAlign(vg, align)
        applyColor(color)
        nvgFillColor(vg, nvgColor)
        nvgText(vg, x, y, text)
    }

    @JvmStatic
    @JvmOverloads
    fun textWidth(text: String, size: Float, font: Font? = defaultFont): Float {
        if (font == null || vg == -1L) return 0f
        nvgFontSize(vg, size)
        nvgFontFaceId(vg, getFontID(font))
        return nvgTextBounds(vg, 0f, 0f, text, fontBounds)
    }

    private fun getFontID(font: Font): Int {
        return fontMap.getOrPut(font) {
            val buffer = font.buffer()
            NVGFont(nvgCreateFontMem(vg, font.name, buffer, false), buffer)
        }.id
    }

    @JvmStatic
    fun destroy() {
        clearImageCache()

        fontMap.values.forEach {}
        fontMap.clear()

        if (vg != -1L) {
            nvgDelete(vg)
            vg = -1L
        }
    }
}