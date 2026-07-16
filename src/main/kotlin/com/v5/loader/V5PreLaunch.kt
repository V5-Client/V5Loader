package com.v5.loader

import com.v5.loader.internal.V5Loader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint

class V5PreLaunch : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        V5Loader.init()
    }
}
