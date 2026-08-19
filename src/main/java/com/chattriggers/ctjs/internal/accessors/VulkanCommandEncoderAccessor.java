//? if >=26.2 {
package com.chattriggers.ctjs.internal.accessors;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface VulkanCommandEncoderAccessor {
    VkCommandBuffer ctjs$getCommandBuffer();
}
//?}
