//? if >=26.2 {
package com.chattriggers.ctjs.internal.accessors;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;

public interface GpuDeviceAccessor {
    GpuDeviceBackend ctjs$getBackend();
}
//?}
