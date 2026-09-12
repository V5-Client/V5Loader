package com.chattriggers.ctjs.api.render.skia

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SkijaBootstrap : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        val artifact = when {
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
        FabricLauncherBase.getLauncher().addToClassPath(file)
    }

    private companion object {
        const val VERSION = "0.143.11"
    }
}
