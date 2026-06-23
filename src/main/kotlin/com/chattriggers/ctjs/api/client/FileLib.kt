package com.chattriggers.ctjs.api.client

import com.chattriggers.ctjs.CTJS
import net.minecraft.util.Util
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.Base64
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object FileLib {
    /**
     * Writes a file to folder in modules.
     *
     * @param importName name of the import
     * @param fileName name of the file
     * @param toWrite string to write in file
     * @param recursive whether to create folders to the file location if they don't exist
     */
    @JvmStatic
    @JvmOverloads
    fun write(importName: String, fileName: String, toWrite: String, recursive: Boolean = false) {
        write(absoluteLocation(importName, fileName), toWrite, recursive)
    }

    /**
     * Writes a file to anywhere on the system.
     * Use "./" for the ".minecraft" folder.
     *
     * @param fileLocation the location and file name
     * @param toWrite string to write in file
     * @param recursive whether to create folders to the file location if they don't exist
     */
    @JvmStatic
    @JvmOverloads
    fun write(fileLocation: String, toWrite: String, recursive: Boolean = false) {
        val path = Path.of(fileLocation)
        if (recursive) {
            path.parent?.let(Files::createDirectories)
        }
        Files.writeString(path, toWrite)
    }

    /**
     * Writes a file to folder in modules.
     *
     * @param importName name of the import
     * @param fileName name of the file
     * @param toAppend string to append in file
     */
    @JvmStatic
    fun append(importName: String, fileName: String, toAppend: String) {
        append(absoluteLocation(importName, fileName), toAppend)
    }

    /**
     * Writes a file to anywhere on the system.
     * Use "./" for the ".minecraft" folder.
     *
     * @param fileLocation the location and file name
     * @param toAppend string to append in file
     */
    @JvmStatic
    fun append(fileLocation: String, toAppend: String) {
        Files.writeString(Path.of(fileLocation), toAppend, CREATE, APPEND)
    }

    /**
     * Reads a file from folder in modules.
     * Returns null if file is not found.
     *
     * @param importName name of the import
     * @param fileName name of the file
     * @return the string in the file, or null if not found
     */
    @JvmStatic
    fun read(importName: String, fileName: String): String? {
        return read(File(absoluteLocation(importName, fileName)))
    }

    /**
     * Reads a file from anywhere on the system.
     * Use "./" for the ".minecraft" folder.
     * Returns null if file is not found.
     *
     * @param fileLocation the location and file name
     * @return the string in the file, or null if not found
     */
    @JvmStatic
    fun read(fileLocation: String): String? {
        return read(File(fileLocation))
    }

    /**
     * Reads a file from anywhere on the system using java.io.File.
     *
     * @param file the java.io.File to read
     * @return the string in the file, or null if not found
     */
    @JvmStatic
    fun read(file: File): String? {
        return runCatching { Files.readString(file.toPath()) }.getOrNull()
    }

    /**
     * Determines if a file or directory exists at the specified location
     *
     * @param importName name of the import
     * @param fileName name of the file
     * @return if the file exists
     */
    @JvmStatic
    fun exists(importName: String, fileName: String): Boolean {
        return exists(absoluteLocation(importName, fileName))
    }

    /**
     * Determines if a file or directory exists at the specified location
     *
     * @param fileLocation the path of the file
     * @return if the file exists
     */
    @JvmStatic
    fun exists(fileLocation: String): Boolean {
        return Files.exists(Path.of(fileLocation))
    }

    /**
     * Determines if a file or directory exists at the specified location
     *
     * @param importName name of the import
     * @param fileName name of the file
     * @return if the location is a directory
     */
    @JvmStatic
    fun isDirectory(importName: String, fileName: String): Boolean {
        return isDirectory(absoluteLocation(importName, fileName))
    }

    /**
     * Determines if a file or directory exists at the specified location
     *
     * @param fileLocation the path of the file
     * @return if the location is a directory
     */
    @JvmStatic
    fun isDirectory(fileLocation: String): Boolean {
        return Files.isDirectory(Path.of(fileLocation))
    }

    /**
     * Gets the contents of a url as a string.
     *
     * @param theUrl the url to get the data from
     * @param userAgent the user agent to use in the connection
     * @return the string stored in the url content
     */
    @Throws(UnknownHostException::class)
    @JvmStatic
    @JvmOverloads
    fun getUrlContent(theUrl: String, userAgent: String? = "Mozilla/5.0"): String {
        val conn = CTJS.makeWebRequest(theUrl, userAgent)

        return conn.getInputStream().use {
            it.readBytes()
        }.toString(StandardCharsets.UTF_8)
    }

    /**
     * Deletes a file at the specified location
     *
     * @param importName name of the import
     * @param fileName name of the file
     * @return if the file was deleted
     */
    @JvmStatic
    fun delete(importName: String, fileName: String): Boolean {
        return delete(absoluteLocation(importName, fileName))
    }

    /**
     * Deletes a file at the specified location
     *
     * @param fileLocation the path of the file
     * @return if the file was deleted
     */
    @JvmStatic
    fun delete(fileLocation: String): Boolean {
        return Files.deleteIfExists(Path.of(fileLocation))
    }

    /**
     * Deletes a directory at the specified location
     *
     * @param dir the directory to delete
     * @return if the directory was deleted
     */
    @JvmStatic
    fun deleteDirectory(dir: String): Boolean {
        return deleteDirectory(File(dir))
    }

    /**
     * Deletes a directory at the specified location
     *
     * @param dir the directory to delete
     * @return if the directory was deleted
     */
    @JvmStatic
    fun deleteDirectory(dir: File): Boolean {
        val paths = runCatching { Files.walk(dir.toPath()) }.getOrElse { return false }

        return try {
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            true
        } catch (_: IOException) {
            false
        } finally {
            paths.close()
        }
    }

    /**
     * Extracts a zip file specified by the zipFilePath to a directory specified by
     * destDirectory (will be created if does not exist).
     * @param zipFilePath the zip file path
     * @param destDirectory the destination directory
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    @JvmStatic
    fun unzip(zipFilePath: String, destDirectory: String) {
        val destPath = Path.of(destDirectory)
        Files.createDirectories(destPath)

        ZipInputStream(Files.newInputStream(Path.of(zipFilePath))).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val outputPath = destPath.resolve(entry.name).normalize()
                if (!outputPath.startsWith(destPath)) {
                    throw IOException("Zip entry escapes target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    Files.createDirectories(outputPath)
                } else {
                    outputPath.parent?.let(Files::createDirectories)
                    Files.newOutputStream(outputPath).use(zipIn::copyTo)
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private fun absoluteLocation(importName: String, fileLocation: String): String {
        return CTJS.MODULES_FOLDER + File.separator + importName + File.separator + fileLocation
    }

    /**
     * Encodes a string to a base64 string
     *
     * @param toEncode string to encode
     * @return base64 encoded string
     */
    @JvmStatic
    fun encodeBase64(toEncode: String): String {
        return Base64.getEncoder().encodeToString(toEncode.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Decodes a base64 string to a string
     *
     * @param toDecode base64 encoded string to decode
     * @return decoded string
     */
    @JvmStatic
    fun decodeBase64(toDecode: String): String {
        return String(Base64.getDecoder().decode(toDecode), StandardCharsets.UTF_8)
    }

    /**
     * Opens a url in the default browser
     *
     * @param url the url to open
     */
    @JvmStatic
    fun open(url: String) {
        Util.getOperatingSystem().open(url)
    }

    /**
     * Opens a path in the file explorer
     *
     * @param path the path to open
     */
    @JvmStatic
    fun open(path: File) {
        Util.getOperatingSystem().open(path)
    }
}
