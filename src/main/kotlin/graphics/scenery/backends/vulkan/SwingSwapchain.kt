package graphics.scenery.backends.vulkan

import graphics.scenery.Hub
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.SceneryJPanel
import graphics.scenery.utils.SceneryPanel
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memFree
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR
import org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR
import org.lwjgl.vulkan.awt.AWTVKCanvas
import org.lwjgl.vulkan.awt.VKData
import vkk.VkImageAspect
import vkk.VkImageLayout
import vkk.entities.VkImage
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.*
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * GLFW-based default Vulkan Swapchain and window, residing on [device], associated with [queue].
 * Needs to be given [commandPools] to allocate command buffers from. [useSRGB] determines whether
 * the sRGB colorspace will be used, [vsync] determines whether vertical sync will be forced (swapping
 * rendered images in sync with the screen's frequency). [undecorated] determines whether the created
 * window will have the window system's default chrome or not.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class SwingSwapchain(open val device: VulkanDevice,
                          open val queue: VkQueue,
                          open val commandPools: VulkanRenderer.CommandPools,
                          @Suppress("unused") open val renderConfig: RenderConfigReader.RenderConfig,
                          open val useSRGB: Boolean = true,
                          open val vsync: Boolean = false,
                          open val undecorated: Boolean = false) : Swapchain {
    protected val logger by LazyLogger()

    /** Swapchain handle. */
    override var handle: Long = 0L
    /** Array for rendered images. */
    override var images: LongArray = LongArray(0)
    /** Array for image views. */
    override var imageViews: LongArray = LongArray(0)
    /** Number of frames presented with this swapchain. */
    protected var presentedFrames: Long = 0

    /** Color format for the swapchain images. */
    override var format: Int = 0

    /** Swapchain image. */
    var swapchainImage: IntBuffer = MemoryUtil.memAllocInt(1)
    /** Pointer to the current swapchain. */
    var swapchainPointer: LongBuffer = MemoryUtil.memAllocLong(1)
    /** Present info, allocated only once and reused. */
    var presentInfo: VkPresentInfoKHR = VkPresentInfoKHR.calloc()
    /** Vulkan queue used exclusively for presentation. */
    lateinit var presentQueue: VkQueue

    /** Surface of the window to render into. */
    open var surface: Long = 0
    /** [SceneryWindow] instance we are using. */
    lateinit var window: SceneryWindow
    var sceneryPanel: SceneryPanel? = null

    /** Time in ns of the last resize event. */
    var lastResize = -1L
    private val WINDOW_RESIZE_TIMEOUT = 200 * 10e6

    private val retiredSwapchains: Queue<Pair<VulkanDevice, Long>> = ArrayDeque()

    /**
     * Data class for summarising [colorFormat] and [colorSpace] information.
     */
    data class ColorFormatAndSpace(var colorFormat: Int = 0, var colorSpace: Int = 0)


    /**
     * Creates a window for this swapchain, and initialiases [win] as [SceneryWindow.GLFWWindow].
     * Needs to be handed a [VulkanRenderer.SwapchainRecreator].
     * Returns the initialised [SceneryWindow].
     */
    override fun createWindow(win: SceneryWindow, swapchainRecreator: VulkanRenderer.SwapchainRecreator): SceneryWindow {
        val data = VKData()
        data.instance = device.instance
        logger.info("Instance=${data.instance}")

        val p = sceneryPanel as? SceneryJPanel ?: throw IllegalArgumentException("Must have SwingWindow")

        val canvas = object : AWTVKCanvas(data) {
            private val serialVersionUID = 1L
            var initialized: Boolean = false
                private set

            override fun initVK() {
                logger.info("Surface set to $surface")
                this@SwingSwapchain.surface = surface
                this.background = Color.BLACK
                initialized = true
            }

            override fun paintVK() {}
        }

        p.component = canvas
        p.layout = BorderLayout()
        p.add(canvas, BorderLayout.CENTER)
        p.preferredSize = Dimension(win.width, win.height)

        val frame = SwingUtilities.getAncestorOfClass(JFrame::class.java, p) as JFrame
        logger.info("Frame: $frame")
        frame.preferredSize = Dimension(win.width, win.height)
        frame.layout = BorderLayout()
        frame.pack()
        frame.isVisible = true

        while (!canvas.initialized) {
            Thread.sleep(100)
        }

        window = SceneryWindow.SwingWindow(p)
        window.width = win.width
        window.height = win.height

        return window
    }

    /**
     * Finds the best supported presentation mode, given the supported modes in [presentModes].
     * The preferred mode can be selected via [preferredMode]. Returns the preferred mode, or
     * VK_PRESENT_MODE_FIFO, if the preferred one is not supported.
     */
    private fun findBestPresentMode(presentModes: IntBuffer, count: Int, preferredMode: Int): Int {
        val modes = IntArray(count)
        presentModes.get(modes)

        return if (modes.contains(preferredMode)) {
            preferredMode
        } else {
            KHRSurface.VK_PRESENT_MODE_FIFO_KHR
        }
    }

    /**
     * Creates a new swapchain and returns it, potentially recycling or deallocating [oldSwapchain].
     */
    override fun create(oldSwapchain: Swapchain?): Swapchain {
        presentedFrames = 0
        return stackPush().use { stack ->
            val colorFormatAndSpace = getColorFormatAndSpace()
            val oldHandle = oldSwapchain?.handle

            // Get physical device surface properties and formats
            val surfCaps = VkSurfaceCapabilitiesKHR.callocStack(stack)

            VU.run("Getting surface capabilities",
                { KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physicalDevice, surface, surfCaps) })

            val presentModeCount = VU.getInts("Getting present mode count", 1
            ) { KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice, surface, this, null) }

            val presentModes = VU.getInts("Getting present modes", presentModeCount.get(0)
            ) { KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice, surface, presentModeCount, this) }

            // use fifo mode (aka, vsynced) if requested,
            // otherwise, use mailbox mode and present the most recently generated frame.
            val preferredSwapchainPresentMode = if (vsync) {
                KHRSurface.VK_PRESENT_MODE_FIFO_KHR
            } else {
                KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR
            }

            val swapchainPresentMode = findBestPresentMode(presentModes,
                presentModeCount.get(0),
                preferredSwapchainPresentMode)

            // Determine the number of images
            var desiredNumberOfSwapchainImages = surfCaps.minImageCount() + 1
            if (surfCaps.maxImageCount() in 1..(desiredNumberOfSwapchainImages - 1)) {
                desiredNumberOfSwapchainImages = surfCaps.maxImageCount()
            }

            val currentWidth = surfCaps.currentExtent().width()
            val currentHeight = surfCaps.currentExtent().height()

            if (currentWidth > 0 && currentHeight > 0) {
                window.width = currentWidth
                window.height = currentHeight
            } else {
                // TODO: Better default values
                window.width = 1920
                window.height = 1200
            }

            val preTransform = if (surfCaps.supportedTransforms() and KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR != 0) {
                KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            } else {
                surfCaps.currentTransform()
            }

            val swapchainCI = VkSwapchainCreateInfoKHR.callocStack(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .pNext(MemoryUtil.NULL)
                .surface(surface)
                .minImageCount(desiredNumberOfSwapchainImages)
                .imageFormat(colorFormatAndSpace.colorFormat)
                .imageColorSpace(colorFormatAndSpace.colorSpace)
                .imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .preTransform(preTransform)
                .imageArrayLayers(1)
                .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .pQueueFamilyIndices(null)
                .presentMode(swapchainPresentMode)
                .clipped(true)
                .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)

            if ((oldSwapchain is VulkanSwapchain || oldSwapchain is FXSwapchain) && oldHandle != null) {
                swapchainCI.oldSwapchain(oldHandle)
            }

            swapchainCI.imageExtent().width(window.width).height(window.height)

            handle = VU.getLong("Creating swapchain",
                { KHRSwapchain.vkCreateSwapchainKHR(device.vulkanDevice, swapchainCI, null, this) }, {})

            // If we just re-created an existing swapchain, we should destroy the old swapchain at this point.
            // Note: destroying the swapchain also cleans up all its associated presentable images once the platform is done with them.
            if (oldSwapchain is VulkanSwapchain && oldHandle != null && oldHandle != VK10.VK_NULL_HANDLE) {
                // TODO: Figure out why deleting a retired swapchain crashes on Nvidia
//                KHRSwapchain.vkDestroySwapchainKHR(device.vulkanDevice, oldHandle, null)
                retiredSwapchains.add(device to oldHandle)
            }

            val imageCount = VU.getInts("Getting swapchain images", 1) {
                KHRSwapchain.vkGetSwapchainImagesKHR(device.vulkanDevice, handle, this, null)
            }

            logger.debug("Got ${imageCount.get(0)} swapchain images")

            val swapchainImages = VU.getLongs("Getting swapchain images", imageCount.get(0),
                { KHRSwapchain.vkGetSwapchainImagesKHR(device.vulkanDevice, handle, imageCount, this) }, {})

            val images = LongArray(imageCount.get(0))
            val imageViews = LongArray(imageCount.get(0))
            val colorAttachmentView = VkImageViewCreateInfo.callocStack(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .pNext(MemoryUtil.NULL)
                .format(colorFormatAndSpace.colorFormat)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .flags(0)

            colorAttachmentView.components()
                .r(VK10.VK_COMPONENT_SWIZZLE_R)
                .g(VK10.VK_COMPONENT_SWIZZLE_G)
                .b(VK10.VK_COMPONENT_SWIZZLE_B)
                .a(VK10.VK_COMPONENT_SWIZZLE_A)

            colorAttachmentView.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            with(VU.newCommandBuffer(device, commandPools.Standard.L, autostart = true)) {
                for (i in 0 until imageCount.get(0)) {
                    images[i] = swapchainImages.get(i)

                    VU.setImageLayout(this, VkImage(images[i]),
                        aspectMask = VkImageAspect.COLOR_BIT,
                        oldImageLayout = VkImageLayout.UNDEFINED,
                        newImageLayout = VkImageLayout.PRESENT_SRC_KHR)
                    colorAttachmentView.image(images[i])

                    imageViews[i] = VU.getLong("create image view",
                        { VK10.vkCreateImageView(this@SwingSwapchain.device.vulkanDevice, colorAttachmentView, null, this) }, {})
                }

                endCommandBuffer(this@SwingSwapchain.device, commandPools.Standard.L, queue,
                    flush = true, dealloc = true)
            }

            this.images = images
            this.imageViews = imageViews
            this.format = colorFormatAndSpace.colorFormat

            memFree(swapchainImages)
            memFree(imageCount)
            memFree(presentModeCount)
            memFree(presentModes)

            this
        }
    }

    /**
     * Returns the [ColorFormatAndSpace] supported by the [device].
     */
    protected fun getColorFormatAndSpace(): ColorFormatAndSpace {
        return stackPush().use { stack ->
            val queueFamilyPropertyCount = stack.callocInt(1)
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.physicalDevice, queueFamilyPropertyCount, null)

            val queueCount = queueFamilyPropertyCount.get(0)
            val queueProps = VkQueueFamilyProperties.callocStack(queueCount, stack)
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.physicalDevice, queueFamilyPropertyCount, queueProps)

            // Iterate over each queue to learn whether it supports presenting:
            val supportsPresent = (0 until queueCount).map {
                VU.getInt("Physical device surface support") {
                    KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device.physicalDevice, it, surface, this)
                }
            }

            // Search for a graphics and a present queue in the array of queue families, try to find one that supports both
            var graphicsQueueNodeIndex = Integer.MAX_VALUE
            var presentQueueNodeIndex = Integer.MAX_VALUE

            for (i in 0 until queueCount) {
                if (queueProps.get(i).queueFlags() and VK10.VK_QUEUE_GRAPHICS_BIT != 0) {
                    if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
                        graphicsQueueNodeIndex = i
                    }
                    if (supportsPresent[i] == VK10.VK_TRUE) {
                        graphicsQueueNodeIndex = i
                        presentQueueNodeIndex = i
                        break
                    }
                }
            }

            if (presentQueueNodeIndex == Integer.MAX_VALUE) {
                // If there's no queue that supports both present and graphics try to find a separate present queue
                for (i in 0 until queueCount) {
                    if (supportsPresent[i] == VK10.VK_TRUE) {
                        presentQueueNodeIndex = i
                        break
                    }
                }
            }

            // Generate error if could not find both a graphics and a present queue
            if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
                throw RuntimeException("No graphics queue found")
            }
            if (presentQueueNodeIndex == Integer.MAX_VALUE) {
                throw RuntimeException("No presentation queue found")
            }
            if (graphicsQueueNodeIndex != presentQueueNodeIndex) {
                throw RuntimeException("Presentation queue != graphics queue")
            }

            presentQueue = VkQueue(VU.getPointer("Get present queue",
                { VK10.vkGetDeviceQueue(device.vulkanDevice, presentQueueNodeIndex, 0, this); VK10.VK_SUCCESS }, {}),
                device.vulkanDevice)

            // Get list of supported formats
            val formatCount = VU.getInts("Getting supported surface formats", 1,
                { KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice, surface, this, null) })

            val surfFormats = VkSurfaceFormatKHR.callocStack(formatCount.get(0), stack)
            VU.run("Query device physical surface formats",
                { KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice, surface, formatCount, surfFormats) })

            val colorFormat = if (formatCount.get(0) == 1 && surfFormats.get(0).format() == VK10.VK_FORMAT_UNDEFINED) {
                if (useSRGB) {
                    VK10.VK_FORMAT_B8G8R8A8_SRGB
                } else {
                    VK10.VK_FORMAT_B8G8R8A8_UNORM
                }
            } else {
                if (useSRGB) {
                    VK10.VK_FORMAT_B8G8R8A8_SRGB
                } else {
                    VK10.VK_FORMAT_B8G8R8A8_UNORM
                }
            }

            val colorSpace = if (useSRGB) {
                KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
            } else {
                surfFormats.get(0).colorSpace()
            }

            memFree(formatCount)

            ColorFormatAndSpace(colorFormat, colorSpace)
        }
    }

    /**
     * Presents the current swapchain image on screen.
     */
    override fun present(waitForSemaphores: LongBuffer?) {
        // Present the current buffer to the swap chain
        // This will display the image
        swapchainPointer.put(0, handle)

        // Info struct to present the current swapchain image to the display
        presentInfo
            .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            .pNext(MemoryUtil.NULL)
            .swapchainCount(swapchainPointer.remaining())
            .pSwapchains(swapchainPointer)
            .pImageIndices(swapchainImage)
            .pResults(null)

        waitForSemaphores?.let { presentInfo.pWaitSemaphores(it) }

        // here we accept the VK_ERROR_OUT_OF_DATE_KHR error code, which
        // seems to spuriously occur on Linux upon resizing.
        VU.run("Presenting swapchain image",
            { KHRSwapchain.vkQueuePresentKHR(presentQueue, presentInfo) },
            allowedResults = listOf(VK_ERROR_OUT_OF_DATE_KHR))

        presentedFrames++
    }

    /**
     * To be called after presenting, will deallocate retired swapchains.
     */
    override fun postPresent(image: Int) {
        while (retiredSwapchains.isNotEmpty()) {
            retiredSwapchains.poll()?.let {
                KHRSwapchain.vkDestroySwapchainKHR(it.first.vulkanDevice, it.second, null)
            }
        }
    }

    /**
     * Acquires the next swapchain image.
     */
    override fun next(timeout: Long, signalSemaphore: Long): Boolean {
        // wait for the present queue to become idle - by doing this here
        // we avoid stalling the GPU and gain a few FPS
        VK10.vkQueueWaitIdle(presentQueue)

        val err = vkAcquireNextImageKHR(device.vulkanDevice, handle, timeout,
            signalSemaphore,
            VK10.VK_NULL_HANDLE, swapchainImage)

        if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
            return true
        } else if (err != VK10.VK_SUCCESS) {
            throw AssertionError("Failed to acquire next swapchain image: " + VU.translate(err))
        }

        return false
    }

    /**
     * Changes the current window to fullscreen.
     */
    override fun toggleFullscreen(hub: Hub, swapchainRecreator: VulkanRenderer.SwapchainRecreator) {
        // TODO: Add
    }

    /**
     * Embeds the swapchain into a [SceneryPanel]. Not supported by [VulkanSwapchain],
     * see [FXSwapchain] instead.
     */
    override fun embedIn(panel: SceneryPanel?) {
        if (panel == null) {
            return
        }

        sceneryPanel = panel
    }

    /**
     * Returns the number of fully presented frames.
     */
    override fun presentedFrames(): Long {
        return presentedFrames
    }

    /**
     * Closes the swapchain, deallocating all of its resources.
     */
    override fun close() {
        logger.debug("Closing swapchain $this")
        KHRSwapchain.vkDestroySwapchainKHR(device.vulkanDevice, handle, null)

        presentInfo.free()
        MemoryUtil.memFree(swapchainImage)
        MemoryUtil.memFree(swapchainPointer)
    }
}
