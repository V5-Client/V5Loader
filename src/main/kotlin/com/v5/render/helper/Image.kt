package com.v5.render.helper

import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files

class Image(
    val identifier: String,
    private var stream: InputStream? = null,
    private var buffer: ByteBuffer? = null
) {
    val isSVG: Boolean = identifier.endsWith(".svg", true)
    val isGIF: Boolean = identifier.endsWith(".gif", true)

    init {
        if (stream == null && buffer == null) {
            stream = getStream(identifier)
        }
    }

    fun buffer(): ByteBuffer {
        if (buffer == null) {
            val inputStream = stream ?: throw IllegalStateException("Image has no stream")
            val bytes = inputStream.readBytes()
            buffer = MemoryUtil.memAlloc(bytes.size).put(bytes).flip() as ByteBuffer
            inputStream.close()
            stream = null
        }
        return buffer ?: throw IllegalStateException("Image has no data")
    }

    fun getInputStream(): InputStream {
        if (stream == null) {
            if (buffer != null) {
                val bytes = ByteArray(buffer!!.remaining())
                val pos = buffer!!.position()
                buffer!!.get(bytes)
                buffer!!.position(pos)
                return bytes.inputStream()
            }
            stream = getStream(identifier)
        }
        return stream!!
    }

    fun close() {
        stream?.close()
        buffer?.let {
            if (MemoryUtil.memAddressSafe(it) != 0L) {
                MemoryUtil.memFree(it)
            }
        }
        buffer = null
        stream = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Image) return false
        return identifier == other.identifier
    }

    override fun hashCode(): Int = identifier.hashCode()

    companion object {
        fun fromPath(path: String): Image = Image(path)

        fun fromStream(identifier: String, stream: InputStream): Image = Image(identifier, stream)

        private fun getStream(path: String): InputStream {
            val trimmedPath = path.trim()
            return when {
                trimmedPath.startsWith("http://") || trimmedPath.startsWith("https://") -> {
                    URL(trimmedPath).openStream()
                }
                else -> {
                    val file = File(trimmedPath)
                    if (file.exists() && file.isFile) {
                        Files.newInputStream(file.toPath())
                    } else {
                        Image::class.java.getResourceAsStream(trimmedPath)
                            ?: throw FileNotFoundException("Cannot find image: $trimmedPath")
                    }
                }
            }
        }
    }
}