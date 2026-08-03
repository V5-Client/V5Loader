package com.chattriggers.ctjs.api.render

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import net.minecraft.client.gui.render.TextureSetup
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FT_Vector
import org.lwjgl.util.freetype.FreeType.FT_Done_Face
import org.lwjgl.util.freetype.FreeType.FT_Done_FreeType
import org.lwjgl.util.freetype.FreeType.FT_Get_Char_Index
import org.lwjgl.util.freetype.FreeType.FT_Get_Kerning
import org.lwjgl.util.freetype.FreeType.FT_Init_FreeType
import org.lwjgl.util.freetype.FreeType.FT_KERNING_UNSCALED
import org.lwjgl.util.freetype.FreeType.FT_LOAD_DEFAULT
import org.lwjgl.util.freetype.FreeType.FT_Load_Glyph
import org.lwjgl.util.freetype.FreeType.FT_New_Memory_Face
import org.lwjgl.util.freetype.FreeType.FT_RENDER_MODE_NORMAL
import org.lwjgl.util.freetype.FreeType.FT_Render_Glyph
import org.lwjgl.util.freetype.FreeType.FT_Set_Pixel_Sizes
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** FreeType layout/raster work plus render-thread-owned grayscale atlas pages. */
internal class FreeTypeTextRenderer {
    data class Layout(
        val codePoints: IntArray,
        val xs: FloatArray,
        val lines: IntArray,
        val lineWidths: FloatArray,
        val width: Float,
        val ascender: Float,
        val lineHeight: Float,
    ) {
        val height get() = lineHeight * lineWidths.size
        internal val glyphRuns = HashMap<Int, GlyphRun>()
    }

    internal class GlyphRun(size: Int) {
        val glyphs = arrayOfNulls<Glyph>(size)
        val resolved = BooleanArray(size)
        var complete = false
    }

    data class Glyph(
        val page: AtlasPage,
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int,
    )

    internal class AtlasPage(
        val texture: GpuTexture,
        val view: GpuTextureView,
        val setup: TextureSetup,
    ) : AutoCloseable {
        var x = PADDING
        var y = PADDING
        var rowHeight = 0
        override fun close() { view.close(); texture.close() }
    }

    private data class LayoutKey(val font: Font, val text: String)
    private data class GlyphKey(val font: Font, val codePoint: Int, val pixelSize: Int)
    private data class BitmapGlyph(val key: GlyphKey, val pixels: ByteArray, val width: Int, val height: Int, val left: Int, val top: Int)
    private data class FaceData(val face: FT_Face, val bytes: ByteBuffer, var pixelSize: Int = 0) {
        val units = (face.units_per_EM().toInt() and 0xffff).coerceAtLeast(1)
        private val verticalUnits = (face.ascender() - face.descender()).coerceAtLeast(1)
        val ascender = face.ascender().toFloat() * BASE_SIZE / verticalUnits
        val lineHeight = max(face.height().toInt(), verticalUnits).toFloat() * BASE_SIZE / verticalUnits
        val kernings = HashMap<Long, Float>()
    }

    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "V5 FreeType").apply { isDaemon = true } }
    private val layouts = object : LinkedHashMap<LayoutKey, Layout>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, Layout>?) = size > MAX_LAYOUTS
    }
    private val pendingLayouts = ConcurrentHashMap<LayoutKey, CompletableFuture<Layout>>()
    private val requestedGlyphs = ConcurrentHashMap.newKeySet<GlyphKey>()
    private val completedGlyphs = ConcurrentLinkedQueue<BitmapGlyph>()
    private val glyphs = ConcurrentHashMap<GlyphKey, Glyph>()
    private val blankGlyphs = ConcurrentHashMap.newKeySet<GlyphKey>()
    private val pages = ArrayList<AtlasPage>()
    private val closed = AtomicBoolean()
    private var library = 0L
    private val faces = HashMap<Font, FaceData>()

    fun layout(text: String, font: Font, pixelSize: Int? = null): Layout? {
        val key = LayoutKey(font, text)
        synchronized(layouts) { layouts[key] }?.let { result ->
            if (pixelSize != null) requestGlyphs(result, font, pixelSize)
            return result
        }
        requestLayout(key, pixelSize)
        return null
    }

    fun measure(text: String, font: Font): Layout {
        val key = LayoutKey(font, text)
        synchronized(layouts) { layouts[key] }?.let { return it }
        return requestLayout(key, null).join()
    }

    fun glyphs(layout: Layout, font: Font, pixelSize: Int): Array<Glyph?> = synchronized(layout.glyphRuns) {
        val run = layout.glyphRuns.getOrPut(pixelSize) { GlyphRun(layout.codePoints.size) }
        if (!run.complete) {
            var complete = true
            for (i in layout.codePoints.indices) if (!run.resolved[i]) {
                val key = GlyphKey(font, layout.codePoints[i], pixelSize)
                val glyph = glyphs[key]
                if (glyph != null) { run.glyphs[i] = glyph; run.resolved[i] = true }
                else if (key in blankGlyphs) run.resolved[i] = true
                else complete = false
            }
            run.complete = complete
        }
        run.glyphs
    }

    fun processUploads(limit: Int = 128) {
        if (completedGlyphs.isEmpty()) return
        RenderSystem.assertOnRenderThread()
        repeat(limit) {
            val bitmap = completedGlyphs.poll() ?: return
            if (bitmap.width == 0 || bitmap.height == 0) {
                blankGlyphs += bitmap.key
                return@repeat
            }
            val page = pages.firstOrNull { place(it, bitmap.width, bitmap.height) != null }
                ?: newPage().also { place(it, bitmap.width, bitmap.height) }
            val px = page.x - bitmap.width - PADDING
            val py = page.y
            val upload = MemoryUtil.memAlloc(bitmap.width * bitmap.height * 4)
            for (coverage in bitmap.pixels) upload.put(0xff.toByte()).put(0xff.toByte()).put(0xff.toByte()).put(coverage)
            upload.flip()
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                page.texture, upload, NativeImage.Format.RGBA, 0, 0, px, py, bitmap.width, bitmap.height,
            )
            MemoryUtil.memFree(upload)
            glyphs[bitmap.key] = Glyph(
                page,
                px.toFloat() / PAGE_SIZE, py.toFloat() / PAGE_SIZE,
                (px + bitmap.width).toFloat() / PAGE_SIZE, (py + bitmap.height).toFloat() / PAGE_SIZE,
                bitmap.width, bitmap.height, bitmap.left, bitmap.top,
            )
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            faces.values.forEach { FT_Done_Face(it.face) }
            faces.clear()
            if (library != 0L) FT_Done_FreeType(library)
            library = 0L
        }
        executor.shutdown()
        pages.forEach(AtlasPage::close)
        pages.clear()
    }

    private fun requestGlyphs(layout: Layout, font: Font, pixelSize: Int) {
        for (codePoint in layout.codePoints) {
            val key = GlyphKey(font, codePoint, pixelSize)
            if (!glyphs.containsKey(key) && key !in blankGlyphs && requestedGlyphs.add(key)) submit { completedGlyphs += rasterize(key, face(font)) }
        }
    }

    private fun requestLayout(key: LayoutKey, pixelSize: Int?): CompletableFuture<Layout> =
        pendingLayouts.computeIfAbsent(key) {
            CompletableFuture<Layout>().also { future ->
                submit {
                    try {
                        val result = buildLayout(key.text, face(key.font))
                        synchronized(layouts) { layouts[key] = result }
                        if (pixelSize != null) requestGlyphs(result, key.font, pixelSize)
                        future.complete(result)
                    } catch (error: Throwable) {
                        future.completeExceptionally(error)
                        throw error
                    } finally {
                        pendingLayouts.remove(key, future)
                    }
                }
            }
        }

    private fun submit(task: () -> Unit) {
        if (!closed.get()) executor.execute {
            try { task() } catch (e: Exception) { System.err.println("[V5] FreeType: ${e.message}") }
        }
    }

    private fun face(font: Font): FaceData = faces.getOrPut(font) {
        if (library == 0L) MemoryStack.stackPush().use { stack ->
            val pointer = stack.mallocPointer(1)
            checkFt(FT_Init_FreeType(pointer), "initialize FreeType")
            library = pointer[0]
        }
        val bytes = font.buffer()
        MemoryStack.stackPush().use { stack ->
            val pointer = stack.mallocPointer(1)
            checkFt(FT_New_Memory_Face(library, bytes, 0, pointer), "load ${font.name}")
            FaceData(FT_Face.create(pointer[0]), bytes)
        }
    }

    private fun buildLayout(text: String, data: FaceData): Layout {
        val codePoints = ArrayList<Int>(text.length)
        val xs = ArrayList<Float>(text.length)
        val lines = ArrayList<Int>(text.length)
        val widths = ArrayList<Float>()
        var line = 0
        var x = 0f
        var previous = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (codePoint == '\r'.code) {
                if (offset < text.length && text[offset] == '\n') offset++
                widths += x; line++; x = 0f; previous = 0; continue
            }
            if (codePoint == '\n'.code) { widths += x; line++; x = 0f; previous = 0; continue }
            val glyph = glyphIndex(data.face, codePoint)
            if (previous != 0 && glyph != 0) x += kerning(data, previous, glyph)
            codePoints += codePoint; xs += x; lines += line
            checkFt(FT_Load_Glyph(data.face, glyph, org.lwjgl.util.freetype.FreeType.FT_LOAD_NO_SCALE), "measure glyph")
            x += data.face.glyph()!!.metrics().horiAdvance() * BASE_SIZE / data.units
            previous = glyph
        }
        widths += x
        val lineWidths = widths.toFloatArray()
        return Layout(codePoints.toIntArray(), xs.toFloatArray(), lines.toIntArray(), lineWidths, lineWidths.maxOrNull() ?: 0f, data.ascender, data.lineHeight)
    }

    private fun kerning(data: FaceData, left: Int, right: Int): Float {
        val key = (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)
        return data.kernings.getOrPut(key) {
            MemoryStack.stackPush().use { stack ->
                val value = FT_Vector.malloc(stack)
                if (FT_Get_Kerning(data.face, left, right, FT_KERNING_UNSCALED, value) == 0) value.x() * BASE_SIZE / data.units else 0f
            }
        }
    }

    private fun rasterize(key: GlyphKey, data: FaceData): BitmapGlyph {
        if (data.pixelSize != key.pixelSize) {
            checkFt(FT_Set_Pixel_Sizes(data.face, 0, key.pixelSize), "set ${key.pixelSize}px font size")
            data.pixelSize = key.pixelSize
        }
        val index = glyphIndex(data.face, key.codePoint)
        checkFt(FT_Load_Glyph(data.face, index, FT_LOAD_DEFAULT), "load glyph U+${key.codePoint.toString(16)}")
        val slot = data.face.glyph()!!
        checkFt(FT_Render_Glyph(slot, FT_RENDER_MODE_NORMAL), "render glyph U+${key.codePoint.toString(16)}")
        val bitmap = slot.bitmap()
        val width = bitmap.width()
        val height = bitmap.rows()
        val pixels = ByteArray(width * height)
        if (width > 0 && height > 0) {
            val pitch = bitmap.pitch()
            val source = bitmap.buffer(kotlin.math.abs(pitch) * height)!!
            for (y in 0 until height) {
                val row = if (pitch >= 0) y * pitch else (height - 1 - y) * -pitch
                for (x in 0 until width) pixels[y * width + x] = source[row + x]
            }
        }
        return BitmapGlyph(key, pixels, width, height, slot.bitmap_left(), slot.bitmap_top())
    }

    private fun glyphIndex(face: FT_Face, codePoint: Int): Int {
        val direct = FT_Get_Char_Index(face, codePoint.toLong())
        if (direct != 0) return direct
        return FT_Get_Char_Index(face, 0xfffd).takeIf { it != 0 } ?: 0
    }

    private fun newPage(): AtlasPage {
        val device = RenderSystem.getDevice()
        val texture = device.createTexture(
            { "V5 glyph atlas ${pages.size}" },
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            TextureFormat.RGBA8, PAGE_SIZE, PAGE_SIZE, 1, 1,
        )
        val empty = MemoryUtil.memCalloc(PAGE_SIZE * PAGE_SIZE * 4)
        device.createCommandEncoder().writeToTexture(texture, empty, NativeImage.Format.RGBA, 0, 0, 0, 0, PAGE_SIZE, PAGE_SIZE)
        MemoryUtil.memFree(empty)
        val view = device.createTextureView(texture)
        val sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false)
        return AtlasPage(texture, view, TextureSetup.singleTexture(view, sampler)).also(pages::add)
    }

    private fun place(page: AtlasPage, width: Int, height: Int): Unit? {
        val paddedWidth = width + PADDING
        val paddedHeight = height + PADDING
        if (page.x + paddedWidth > PAGE_SIZE) { page.x = PADDING; page.y += page.rowHeight + PADDING; page.rowHeight = 0 }
        if (page.y + paddedHeight > PAGE_SIZE) return null
        page.x += paddedWidth
        page.rowHeight = max(page.rowHeight, height)
        return Unit
    }

    private fun checkFt(error: Int, action: String) = check(error == 0) { "Failed to $action (FreeType error $error)" }

    companion object {
        const val BASE_SIZE = 8f
        private const val PAGE_SIZE = 1024
        private const val PADDING = 2
        private const val MAX_LAYOUTS = 1024
    }
}
