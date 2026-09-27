package com.chattriggers.ctjs.api.render.skia

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import com.chattriggers.ctjs.internal.utils.Platform
import java.net.URI
import java.nio.file.Path
import java.util.jar.JarFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SkijaBootstrap : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        val arch = System.getProperty("os.arch").lowercase()
        val androidArch = when (arch) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> if (Platform.isAndroid) error("Unsupported Android architecture: $arch") else ""
        }
        val artifact = when {
            Platform.isAndroid && androidArch == "arm64" -> "skija-android-arm64"
            Platform.isAndroid -> "skija-android-x64"
            System.getProperty("os.name").contains("win", true) -> "skija-windows-x64"
            System.getProperty("os.name").contains("mac", true) && System.getProperty("os.arch").contains("aarch64", true) -> "skija-macos-arm64"
            System.getProperty("os.name").contains("mac", true) -> "skija-macos-x64"
            System.getProperty("os.arch").contains("aarch64", true) -> "skija-linux-arm64"
            else -> "skija-linux-x64"
        }
        val cache = FabricLoader.getInstance().gameDir.resolve("config/ChatTriggers/cache")
        val file = cache.resolve("$artifact-$VERSION.jar")
        if (!Files.exists(file)) {
            Files.createDirectories(cache)
            val temporary = Files.createTempFile(cache, "skija-", ".tmp")
            try {
                URI("https://repo1.maven.org/maven2/io/github/humbleui/$artifact/$VERSION/${file.fileName}")
                    .toURL().openStream().use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        if (Platform.isAndroid) {
            val nativeDir = Path.of(System.getProperty("java.io.tmpdir"), "$artifact-$VERSION-native")
            val nativeLibrary = nativeDir.resolve(System.mapLibraryName("skija"))
            if (!Files.exists(nativeLibrary)) {
                Files.createDirectories(nativeDir)
                val temporary = Files.createTempFile(nativeDir, "skija-native-", ".tmp")
                try {
                    JarFile(file.toFile()).use { jar ->
                        val entry = jar.getJarEntry("io/github/humbleui/skija/android/$androidArch/libskija.so")
                            ?: error("Android Skija library is missing for $androidArch")
                        jar.getInputStream(entry).use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
                    }
                    Files.move(temporary, nativeLibrary, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            System.setProperty("skija.library.path", nativeDir.toString())
        }
        FabricLauncherBase.getLauncher().addToClassPath(file)
    }

    private companion object {
        const val VERSION = "0.143.11"
    }
}
