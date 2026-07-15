package com.chattriggers.ctjs.internal.launch

import java.io.File

internal object LegacyLoaderMigration {
    private val legacyLoaderName = Regex("V5ModLoader(?: \\(\\d+\\))?\\.jar", RegexOption.IGNORE_CASE)

    fun stageCachedLoaderIfNeeded(): Boolean {
        val gameDir = System.getProperty("v5.game_dir")?.takeIf(String::isNotBlank)?.let(::File) ?: return false
        val legacyLoaders = File(gameDir, "mods").listFiles()
            ?.filter { it.isFile && legacyLoaderName.matches(it.name) }
            .orEmpty()
        if (legacyLoaders.isEmpty()) return false

        val cachedLoader = runCatching {
            File(CTJSPreLaunch::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile
        }.getOrNull()?.takeIf { it.isFile && it.parentFile?.name == "cache" } ?: return false

        val bytes = runCatching { cachedLoader.readBytes() }.getOrNull() ?: return false
        try {
            ModLoaderUpdater.stageUpdateAndRelaunch(gameDir, bytes, legacyLoaders + cachedLoader)
            println("[V5] Migrating legacy V5ModLoader to V5-Loader. Closing Minecraft now.")
        } finally {
            bytes.fill(0)
        }
        Runtime.getRuntime().halt(0)
        return true
    }
}
