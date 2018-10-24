package graphics.scenery.backends.vulkan

import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.SceneryWindow
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer
import java.nio.LongBuffer
import graphics.scenery.Hub
import graphics.scenery.utils.SceneryPanel
import org.lwjgl.system.MemoryStack
import vkk.VkBufferUsage
import vkk.VkDeviceSize
import vkk.VkMemoryProperty


/**
 * Extended Vulkan swapchain that runs in headless mode.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class HeadlessSwapchain(device: VulkanDevice,
                        queue: VkQueue,
                        commandPools: VulkanRenderer.CommandPools,
                        renderConfig: RenderConfigReader.RenderConfig,
                        useSRGB: Boolean = true,
                        @Suppress("unused") val useFramelock: Boolean = false,
                        @Suppress("unused") val bufferCount: Int = 2) : VulkanSwapchain(device, queue, commandPools, renderConfig, useSRGB) {
    protected var initialized = false
    protected lateinit var sharingBuffer: VulkanBuffer
    protected lateinit var imageBuffer: ByteBuffer

    protected var imagePanel: SceneryPanel? = null

    protected lateinit var vulkanInstance: VkInstance
    protected lateinit var vulkanSwapchainRecreator: VulkanRenderer.SwapchainRecreator

    protected val WINDOW_RESIZE_TIMEOUT = 600 * 10e5

    /** State variable for the current swapchain image. */
    protected var currentImage = 0

    /**
     * Special resize handler for HeadlessSwapchain, as resize events
     * here are externally triggered, outside of the regular event loop.
     */
    inner class ResizeHandler {
        /** Timestamp of the last resize */
        @Volatile
        var lastResize = -1L
        /** Last reported width */
        var lastWidth = 0
        /** Last reported height */
        var lastHeight = 0

        /**
         * Checks whether a resize is necessary and sets the [VulkanRenderer.SwapchainRecreator.mustRecreate]
         * flag if necessary.
         */
        @Synchronized
        fun queryResize() {
            if (lastWidth <= 0 || lastHeight <= 0) {
                lastWidth = Math.max(1, lastWidth)
                lastHeight = Math.max(1, lastHeight)
                return
            }

            if (lastResize > 0L && lastResize + WINDOW_RESIZE_TIMEOUT < System.nanoTime()) {
                logger.debug("Not resizing, before timeout")
                lastResize = System.nanoTime()
                return
            }

            if (lastWidth == window.width && lastHeight == window.height) {
                return
            }

            logger.debug("Resizing swapchain ${window.width}x${window.height} -> ${lastWidth}x$lastHeight")
            window.width = lastWidth
            window.height = lastHeight

            vulkanSwapchainRecreator.mustRecreate = true

            lastResize = -1L
        }
    }

    protected var resizeHandler = ResizeHandler()

    /**
     * Creates a window for this swapchain, and initialiases [win] as [SceneryWindow.HeadlessWindow].
     * In this case, only a proxy window is used, without any actual window creation.
     */
    override fun createWindow(win: SceneryWindow, swapchainRecreator: VulkanRenderer.SwapchainRecreator): SceneryWindow {
        vulkanInstance = device.instance
        vulkanSwapchainRecreator = swapchainRecreator
        window = SceneryWindow.HeadlessWindow()

        window.width = win.width
        window.height = win.height

        return window
    }

    /**
     * Creates a new swapchain and returns it, potentially recycling or deallocating [oldSwapchain].
     */
    override fun create(oldSwapchain: Swapchain?): Swapchain {
        presentedFrames = 0
        if (oldSwapchain != null && initialized) {
            MemoryUtil.memFree(imageBuffer)
            sharingBuffer.close()
        }

        val format = if(useSRGB) {
            VK10.VK_FORMAT_B8G8R8A8_SRGB
        } else {
            VK10.VK_FORMAT_B8G8R8A8_UNORM
        }
        presentQueue = VU.createDeviceQueue(device, device.queueIndices.graphicsQueue)

        val textureImages = (0 until bufferCount).map {
            val t = VulkanTexture(device, commandPools, queue, queue, window.width, window.height, 1,
                format, 1)
            val image = t.createImage(window.width, window.height, 1, format,
                VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK10.VK_IMAGE_TILING_OPTIMAL, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 1)
            t to image
        }

        images = textureImages.map {
            it.second.image
        }.toLongArray()

        imageViews = textureImages.map {
            it.first.createImageView(it.second, format)
        }.toLongArray()

        logger.info("Created ${images?.size} swapchain images")

        val imageByteSize = VkDeviceSize(window.width * window.height * 4L)
        imageBuffer = MemoryUtil.memAlloc(imageByteSize.i)
        sharingBuffer = VulkanBuffer(device,
            imageByteSize,
            VkBufferUsage.TRANSFER_DST_BIT.i,
            VkMemoryProperty.HOST_VISIBLE_BIT or VkMemoryProperty.HOST_COHERENT_BIT,
            wantAligned = true)

        imagePanel?.prefWidth = window.width.toDouble()
        imagePanel?.prefHeight = window.height.toDouble()

        resizeHandler.lastWidth = window.width
        resizeHandler.lastHeight = window.height

        initialized = true

        return this
    }

    /**
     * Will signal [signalSemaphore] that the next image is ready for being written to for presenting,
     * optionally waiting for a [timeout] before failing. Returns true if the swapchain needs to be
     * recreated and false if not.
     */
    override fun next(timeout: Long, signalSemaphore: Long): Boolean {
        MemoryStack.stackPush().use { stack ->
            VK10.vkQueueWaitIdle(presentQueue)

            val signal = stack.callocLong(1)
            signal.put(0, signalSemaphore)

            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                endCommandBuffer(this@HeadlessSwapchain.device, commandPools.Standard, presentQueue, signalSemaphores = signal,
                    flush = true, dealloc = true)
            }

            currentImage = ++currentImage % (images?.size ?: 1)
        }

        return false
    }

    /**
     * Presents the current image.
     */
    override fun present(waitForSemaphores: LongBuffer?) {
        MemoryStack.stackPush().use { stack ->
            if (vulkanSwapchainRecreator.mustRecreate) {
                return
            }

            val mask = stack.callocInt(1)
            mask.put(0, VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)

            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                endCommandBuffer(this@HeadlessSwapchain.device, commandPools.Standard, presentQueue,
                    waitSemaphores = waitForSemaphores, waitDstStageMask = mask,
                    flush = true, dealloc = true)
            }
        }

        presentedFrames++
    }

    /**
     * Post-present routine, will copy the rendered image into the [images] array.
     */
    override fun postPresent(image: Int) {
        if (vulkanSwapchainRecreator.mustRecreate && sharingBuffer.initialized()) {
            return
        }

        with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
            val subresource = VkImageSubresourceLayers.calloc()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1)

            val regions = VkBufferImageCopy.calloc(1)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageOffset(VkOffset3D.calloc().set(0, 0, 0))
                .imageExtent(VkExtent3D.calloc().set(window.width, window.height, 1))
                .imageSubresource(subresource)

            val transferImage = images!![image]

            VulkanTexture.transitionLayout(transferImage,
                KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                srcStage = VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                commandBuffer = this)

            VK10.vkCmdCopyImageToBuffer(this, transferImage,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                sharingBuffer.vulkanBuffer.L,
                regions)

            VulkanTexture.transitionLayout(transferImage,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                dstStage = VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                commandBuffer = this)

            endCommandBuffer(this@HeadlessSwapchain.device, commandPools.Standard, queue,
                flush = true, dealloc = true)
        }

        VK10.vkQueueWaitIdle(queue)

        resizeHandler.queryResize()
    }

    /**
     * Queries the resize handler for changes, setting the
     * [VulkanRenderer.SwapchainRecreator.mustRecreate] if necessary.
     */
    fun queryResize() {
        resizeHandler.queryResize()
    }

    /**
     * Toggles fullscreen.
     */
    override fun toggleFullscreen(hub: Hub, swapchainRecreator: VulkanRenderer.SwapchainRecreator) {
        window.isFullscreen = !window.isFullscreen

        resizeHandler.lastWidth = window.width
        resizeHandler.lastHeight = window.height
    }

    /**
     * Embeds the swapchain into a [SceneryPanel].
     */
    override fun embedIn(panel: SceneryPanel?) {
        imagePanel = panel
    }

    /**
     * Closes the swapchain, deallocating all resources.
     */
    override fun close() {
        KHRSwapchain.vkDestroySwapchainKHR(device.vulkanDevice, handle, null)
        KHRSurface.vkDestroySurfaceKHR(device.instance, surface, null)

        presentInfo.free()

        MemoryUtil.memFree(swapchainImage)
        MemoryUtil.memFree(swapchainPointer)
        MemoryUtil.memFree(imageBuffer)

        sharingBuffer.close()
    }
}
