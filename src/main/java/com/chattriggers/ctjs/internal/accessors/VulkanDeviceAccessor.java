//? if >=26.3 {
/*package com.chattriggers.ctjs.internal.accessors;

import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;

public interface VulkanDeviceAccessor {
    VulkanPhysicalDevice ctjs$getPhysicalDevice();
}
*///?} else if >=26.2 {
package com.chattriggers.ctjs.internal.accessors;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;

public interface VulkanDeviceAccessor {
    VulkanPhysicalDevice ctjs$getPhysicalDevice();
}
//?}
