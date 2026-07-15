package com.v5.loader

import com.v5.loader.internal.V5Loader
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint

class V5PreLaunch : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        val fabricLoader = FabricLoader.getInstance()
        System.setProperty("v5.game_dir", fabricLoader.gameDir.toAbsolutePath().toString())
        System.setProperty("v5.minecraft_version", fabricLoader.rawGameVersion)
        V5Loader.init()
    }
}
