//? if >=26.2 {
package com.chattriggers.ctjs.api.render.skia

import com.chattriggers.ctjs.internal.accessors.VulkanDeviceAccessor
import com.chattriggers.ctjs.internal.mixins.CommandEncoderMixin
import com.chattriggers.ctjs.internal.mixins.GpuDeviceMixin
import com.chattriggers.ctjs.internal.mixins.VulkanCommandEncoderMixin
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.slf4j.LoggerFactory

internal class SkijaVulkanSurface : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SkijaVulkanSurface::class.java)
    private var device: VulkanDevice? = null
    private var context: DirectContext? = null
    private var target: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var image = 0L
    private var width = 0
    private var height = 0
    private var format: GpuFormat? = null

    fun render(width: Int, height: Int, texture: GpuTexture, draw: (io.github.humbleui.skija.Canvas) -> Unit): Boolean {
        val vulkanTexture = texture as? VulkanGpuTexture ?: return false
        if (vulkanTexture.isClosed || vulkanTexture.getFormat() != GpuFormat.RGBA8_UNORM ||
            vulkanTexture.usage() and GpuTexture.USAGE_RENDER_ATTACHMENT == 0 ||
            vulkanTexture.usage() and GpuTexture.USAGE_TEXTURE_BINDING == 0
        ) {
            logger.warn("Skipping Skija Vulkan target with unsupported format or usage: {}", texture.getLabel())
            return true
        }

        val gpuDevice = RenderSystem.tryGetDevice() ?: return true
        val backend = (gpuDevice as GpuDeviceMixin).`ctjs$getBackend`()
        val vulkanDevice = backend as? VulkanDevice ?: run {
            logger.warn("GpuTexture is Vulkan but RenderSystem device backend is {}", backend.javaClass.name)
            return true
        }
        val physical = (vulkanDevice as VulkanDeviceAccessor).`ctjs$getPhysicalDevice`()
        if (physical == null) {
            logger.error("Vulkan physical device accessor returned null")
            return true
        }

        try {
            ensureResources(vulkanDevice, physical, vulkanTexture, width, height)
            transition(gpuDevice, vulkanTexture.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT, VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
            draw(surface!!.canvas)
            context!!.flushAndSubmit(false)
            transition(gpuDevice, vulkanTexture.vkImage(), VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT)
        } catch (exception: Throwable) {
            logger.error("Skija Vulkan rendering failed", exception)
            closeResources()
        }
        return true
    }

    private fun ensureResources(vk: VulkanDevice, physical: com.mojang.blaze3d.vulkan.VulkanPhysicalDevice,
                                texture: VulkanGpuTexture, width: Int, height: Int) {
        if (device !== vk) {
            closeResources()
            val instance = vk.instance()
            val instanceProc = org.lwjgl.vulkan.VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr")
            val deviceProc = vk.vkDevice().capabilities.vkGetDeviceProcAddr
            context = DirectContext.makeVulkan(
                instance.vkInstance().address(), physical.vkPhysicalDevice().address(), vk.vkDevice().address(),
                vk.graphicsQueue().vkQueue().address(), vk.graphicsQueue().queueFamilyIndex(), instanceProc, deviceProc, VK12.VK_API_VERSION_1_2
            )
            device = vk
        }
        if (image == texture.vkImage() && this.width == width && this.height == height && format == texture.getFormat()) return
        closeTarget()
        val imageUsage = VulkanConst.textureUsageToVk(texture.usage(), texture.getFormat()) or
            VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
        target = BackendRenderTarget.makeVulkan(width, height, texture.vkImage(), VK10.VK_IMAGE_TILING_OPTIMAL,
            VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VulkanConst.toVk(texture.getFormat()),
            imageUsage, 1, 1)
        surface = Surface.wrapBackendRenderTarget(context!!, target!!, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB())
        image = texture.vkImage()
        this.width = width
        this.height = height
        format = texture.getFormat()
    }

    private fun transition(gpu: GpuDevice, image: Long, oldLayout: Int, newLayout: Int, srcStage: Int, dstStage: Int, srcAccess: Int, dstAccess: Int) {
        val encoder = gpu.createCommandEncoder()
        val vkEncoder = ((encoder as CommandEncoderMixin).`ctjs$getBackend`() as? VulkanCommandEncoder)
            ?: error("Minecraft did not provide a Vulkan command encoder")
        val commandBuffer = (vkEncoder as VulkanCommandEncoderMixin).`ctjs$getCommandBuffer`()
        MemoryStack.stackPush().use { stack ->
            val barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image)
            barrier.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
            VK12.vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier)
        }
        vkEncoder.submit()
    }

    private fun closeTarget() {
        surface?.close(); target?.close()
        surface = null; target = null; image = 0L
    }

    private fun closeResources() {
        closeTarget()
        context?.close()
        context = null; device = null
    }

    override fun close() = closeResources()
}
//?}
