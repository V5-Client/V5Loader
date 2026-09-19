//? if >=26.3 {
/*package com.chattriggers.ctjs.internal.mixins;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceMixin {
    @Accessor("backend")
    GpuDeviceBackend ctjs$getBackend();
}
*///?} else if >=26.2 {
package com.chattriggers.ctjs.internal.mixins;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GpuDevice.class)
public interface GpuDeviceMixin {
    @Accessor("backend")
    GpuDeviceBackend ctjs$getBackend();
}
//?}
