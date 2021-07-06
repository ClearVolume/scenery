package graphics.scenery.backends.vulkan

import com.jogamp.opengl.GL2ES3.GL_MAJOR_VERSION
import com.jogamp.opengl.GL2ES3.GL_MINOR_VERSION
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import graphics.scenery.Hub
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.utils.SceneryPanel
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWNativeGLX.glfwGetGLXWindow
import org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window
import org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display
import org.lwjgl.glfw.GLFWWindowSizeCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GLXNVSwapGroup
import org.lwjgl.opengl.NVDrawVulkanImage
import org.lwjgl.opengl.WGLNVSwapGroup
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.MemoryUtil.memAllocInt
import org.lwjgl.system.Platform
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import java.nio.LongBuffer

/**
 * GLFW-based OpenGL swapchain and window, using Nvidia's NV_draw_vulkan_image GL extension.
 * The swapchain will reside on [device] and submit to [queue]. All other parameters are not used.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class OpenGLSwapchain(device: VulkanDevice,
                      queue: VkQueue,
                      commandPools: VulkanRenderer.CommandPools,
                      renderConfig: RenderConfigReader.RenderConfig,
                      useSRGB: Boolean = true,
                      val useFramelock: Boolean = System.getProperty("scenery.Renderer.Framelock", "false")?.toBoolean() ?: false,
                      val bufferCount: Int = 2,
                      override val undecorated: Boolean = System.getProperty("scenery.Renderer.ForceUndecoratedWindow", "false")?.toBoolean() ?: false) : VulkanSwapchain(device, queue, commandPools, renderConfig, useSRGB) {
    /** Swapchain handle. */
    override var handle: Long = 0L
    /** Array for rendered images. */
    override var images: LongArray = LongArray(0)
    /** Array for image views. */
    override var imageViews: LongArray = LongArray(0)

    /** Color format of the images. */
    override var format: Int = 0

    /** Window instance to use. */
    lateinit override var window: SceneryWindow//.GLFWWindow

    /** List of supported OpenGL extensions. */
    val supportedExtensions = ArrayList<String>()

    private val WINDOW_RESIZE_TIMEOUT = 200 * 10e6

    /**
     * Creates a window for this swapchain, and initialiases [win] as [SceneryWindow.GLFWWindow].
     * Needs to be handed a [VulkanRenderer.SwapchainRecreator].
     * Returns the initialised [SceneryWindow].
     */
    override fun createWindow(win: SceneryWindow, swapchainRecreator: VulkanRenderer.SwapchainRecreator): SceneryWindow {
        glfwDefaultWindowHints()

        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5)
        glfwWindowHint(GLFW_SRGB_CAPABLE, if(useSRGB) { GLFW_TRUE } else { GLFW_FALSE })
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)

        if(undecorated) {
            glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)
        }

        glfwWindowHint(GLFW_STEREO, if (renderConfig.stereoEnabled) {
            GLFW_TRUE
        } else {
            GLFW_FALSE
        })

        val w = glfwCreateWindow(win.width, win.height, "scenery", MemoryUtil.NULL, MemoryUtil.NULL)
        if(w == null) {
            val buffer = PointerBuffer.allocateDirect(255)
            glfwGetError(buffer)
            throw IllegalStateException("Window could not be created: ${buffer.stringUTF8}")
        }
        window = SceneryWindow.GLFWWindow(w).apply {
            glfwSetWindowPos(w, 100, 100)

            // Handle canvas resize
            windowSizeCallback = object : GLFWWindowSizeCallback() {
                override operator fun invoke(window: Long, w: Int, h: Int) {
                    if (lastResize > 0L && lastResize + WINDOW_RESIZE_TIMEOUT < System.nanoTime()) {
                        lastResize = System.nanoTime()
                        return
                    }

                    if (width <= 0 || height <= 0)
                        return

                    width = w
                    height = h
                    swapchainRecreator.mustRecreate = true
                    lastResize = -1L
                }
            }

            glfwSetWindowSizeCallback(window, windowSizeCallback)
            glfwShowWindow(window)
        }
        window.width = win.width
        window.height = win.height

        return window
    }

    /**
     * Creates a new swapchain and returns it, potentially recycling or deallocating [oldSwapchain].
     * In the special case of the [OpenGLSwapchain], an OpenGL context is created and checked for the
     * GL_NV_draw_vulkan_image extension, which it requires.
     */
    override fun create(oldSwapchain: Swapchain?): Swapchain {
        val window = this.window

        if(window !is SceneryWindow.GLFWWindow) {
            throw IllegalStateException("Cannot use a window of type ${window.javaClass.simpleName}")
        }

        presentedFrames = 0
        glfwMakeContextCurrent(window.window)
        GL.createCapabilities()

        logger.info("OpenGL swapchain running OpenGL ${glGetInteger(GL_MAJOR_VERSION)}.${glGetInteger(GL_MINOR_VERSION)} on ${glGetString(GL_RENDERER)}")

        (0 until glGetInteger(GL_NUM_EXTENSIONS)).map {
            supportedExtensions.add(glGetStringi(GL_EXTENSIONS, it) ?: "")
        }

        if (!supportedExtensions.contains("GL_NV_draw_vulkan_image")) {
            logger.error("NV_draw_vulkan_image not supported. Please use standard Vulkan swapchain.")
            throw UnsupportedOperationException("NV_draw_vulkan_image not supported. Please use standard Vulkan swapchain.")
        }

        format = if (useSRGB) {
            VK10.VK_FORMAT_B8G8R8A8_SRGB
        } else {
            VK10.VK_FORMAT_B8G8R8A8_UNORM
        }

        if (window.width <= 0 || window.height <= 0) {
            logger.warn("Received invalid window dimensions, resizing to sane values")
            // TODO: Better default values
            window.width = 2560
            window.height = 1600
        }

        val windowWidth = if(renderConfig.stereoEnabled && window.width < 10000) {
            window.width
        } else {
            window.width
        }

        logger.info("Creating backing images with ${windowWidth}x${window.height}")

        val semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc()
            .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)

        val fenceCreateInfo = VkFenceCreateInfo.calloc()
            .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)

        presentQueue = VU.createDeviceQueue(device, device.queues.graphicsQueue.first)

        val imgs = (0 until bufferCount).map {
            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {

                val t = VulkanTexture(this@OpenGLSwapchain.device, commandPools, queue, queue,
                    windowWidth, window.height, 1, format, 1)

                val image = t.createImage(windowWidth, window.height, 1,
                    format, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_TILING_OPTIMAL, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    1)

                VulkanTexture.transitionLayout(image.image,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 1,
                    commandBuffer = this)

                val view = t.createImageView(image, format)

                imageAvailableSemaphores.add(this@OpenGLSwapchain.device.createSemaphore())
                imageRenderedSemaphores.add(this@OpenGLSwapchain.device.createSemaphore())
                fences.add(VU.getLong("Swapchain image fence", { VK10.vkCreateFence(this@OpenGLSwapchain.device.vulkanDevice, fenceCreateInfo, null, this) }, {}))
                imageUseFences.add(VU.getLong("Swapchain image usage fence", { VK10.vkCreateFence(this@OpenGLSwapchain.device.vulkanDevice, fenceCreateInfo, null, this) }, {}))
                inFlight.add(null)

                endCommandBuffer(this@OpenGLSwapchain.device, commandPools.Standard, queue, flush = true, dealloc = true)
                Pair(image.image, view)
            }
        }

        images = imgs.map { it.first }.toLongArray()
        imageViews = imgs.map { it.second }.toLongArray()

        handle = -1L

        glfwSwapInterval(0)
        glfwShowWindow(window.window)

        glEnable(GL_FRAMEBUFFER_SRGB)

        if (useFramelock) {
            enableFramelock()
        }

        fenceCreateInfo.free()
        semaphoreCreateInfo.free()

        return this
    }

    /**
     * Enables frame-locking for this swapchain, locking buffer swap events to other screens/machines.
     * Works only if the WGL_NV_swap_group (Windows) or GLX_NV_swap_group extension is supported.
     */
    fun enableFramelock(): Boolean {
        val window = this.window

        if(window !is SceneryWindow.GLFWWindow) {
            throw IllegalStateException("Cannot use a window of type ${window.javaClass.simpleName}")
        }

        if (!supportedExtensions.contains("WGL_NV_swap_group") && !supportedExtensions.contains("GLX_NV_swap_group")) {
            logger.warn("Framelock requested, but not supported on this hardware.")
            // TODO: Figure out why K6000 does not report WGL_NV_swap_group correctly.
            // return false
        }

        val swapGroup = System.getProperty("scenery.VulkanRenderer.SwapGroup", "1").toInt()
        val swapBarrier = System.getProperty("scenery.VulkanRenderer.SwapBarrier", "1").toInt()

        val maxGroups = memAllocInt(1)
        val maxBarriers = memAllocInt(1)

        when (Platform.get()) {
            Platform.WINDOWS -> {
                val hwnd = glfwGetWin32Window(window.window)
                val hwndP = WinDef.HWND(Pointer(hwnd))
                val hdc = User32.INSTANCE.GetDC(hwndP)

                WGLNVSwapGroup.wglQueryMaxSwapGroupsNV(Pointer.nativeValue(hdc.pointer), maxGroups, maxBarriers)

                if (!WGLNVSwapGroup.wglJoinSwapGroupNV(Pointer.nativeValue(hdc.pointer), swapGroup)) {
                    logger.error("Failed to bind to swap group $swapGroup")
                    return false
                }

                if (!WGLNVSwapGroup.wglBindSwapBarrierNV(swapGroup, swapBarrier)) {
                    logger.error("Failed to bind to swap barrier $swapBarrier on swap group $swapGroup")
                    return false
                }

                logger.info("Joined swap barrier $swapBarrier on swap group $swapGroup")
                return true
            }

            Platform.LINUX -> {
                val display = glfwGetX11Display()
                val glxWindow = glfwGetGLXWindow(window.window)

                GLXNVSwapGroup.glXQueryMaxSwapGroupsNV(display, 0, maxGroups, maxBarriers)

                if (GLXNVSwapGroup.glXJoinSwapGroupNV(display, glxWindow, swapGroup)) {
                    logger.error("Failed to bind to swap group $swapGroup")
                    return false
                }

                if (GLXNVSwapGroup.glXBindSwapBarrierNV(display, swapGroup, swapBarrier)) {
                    logger.error("Failed to bind to swap barrier $swapBarrier on swap group $swapGroup")
                    return false
                }

                logger.info("Joined swap barrier $swapBarrier on swap group $swapGroup")
                return true
            }

            else -> {
                logger.warn("Hardware Framelock not supported on this platform.")
                return false
            }
        }
    }

    /**
     * Disables frame-lock for this swapchain.
     */
    @Suppress("unused")
    fun disableFramelock() {
        val window = this.window

        if(window !is SceneryWindow.GLFWWindow) {
            throw IllegalStateException("Cannot use a window of type ${window.javaClass.simpleName}")
        }

        if (!supportedExtensions.contains("WGL_NV_swap_group")) {
            logger.warn("Framelock requested, but not supported on this hardware.")
            return
        }

        when (Platform.get()) {
            Platform.WINDOWS -> {
                val hwnd = glfwGetWin32Window(window.window)
                val hwndP = WinDef.HWND(Pointer(hwnd))
                val hdc = User32.INSTANCE.GetDC(hwndP)

                WGLNVSwapGroup.wglJoinSwapGroupNV(Pointer.nativeValue(hdc.pointer), 0)
            }

            Platform.LINUX -> {
                val display = glfwGetX11Display()
                val glxWindow = glfwGetGLXWindow(window.window)

                GLXNVSwapGroup.glXJoinSwapGroupNV(display, glxWindow, 0)
            }

            else -> logger.error("Hardware Framelock not supported on this platform.")
        }

    }

    /**
     * Presents the currently rendered image, drawing the Vulkan image into the current
     * OpenGL context.
     */
    override fun present(waitForSemaphores: LongBuffer?) {
        val window = this.window

        if(window !is SceneryWindow.GLFWWindow) {
            throw IllegalStateException("Cannot use a window of type ${window.javaClass.simpleName}")
        }

        glDisable(GL_DEPTH_TEST)

        waitForSemaphores?.let { NVDrawVulkanImage.glWaitVkSemaphoreNV(waitForSemaphores.get(0)) }

        // note: glDrawVkImageNV expects the OpenGL screen space conventions,
        // so the Vulkan image's ST coordinates have to be flipped
        if (renderConfig.stereoEnabled) {
            glDrawBuffer(GL_BACK_LEFT)
            glClear(GL_COLOR_BUFFER_BIT)
            glDisable(GL_DEPTH_TEST)

            NVDrawVulkanImage.glDrawVkImageNV(images[presentedFrames.toInt() % bufferCount], 0,
                0.0f, 0.0f, window.width.toFloat(), window.height.toFloat(), 0.0f,
                0.0f, 1.0f, 0.5f, 0.0f)

            glDrawBuffer(GL_BACK_RIGHT)
            glClear(GL_COLOR_BUFFER_BIT)
            glDisable(GL_DEPTH_TEST)

            NVDrawVulkanImage.glDrawVkImageNV(images[presentedFrames.toInt() % bufferCount], 0,
                0.0f, 0.0f, window.width.toFloat(), window.height.toFloat(), 0.0f,
                0.5f, 1.0f, 1.0f, 0.0f)
        } else {
            glClear(GL_COLOR_BUFFER_BIT)
            NVDrawVulkanImage.glDrawVkImageNV(images[presentedFrames.toInt() % bufferCount], 0,
                0.0f, 0.0f, window.width.toFloat(), window.height.toFloat(), 0.0f,
                0.0f, 1.0f, 1.0f, 0.0f)
        }

        glfwSwapBuffers(window.window)
        presentedFrames++
    }

    /**
     * Post-present routine, does nothing in this case.
     */
    override fun postPresent(image: Int) {
        currentImage = (currentImage + 1) % images.size
    }

    /**
     * Proceeds to the next swapchain image.
     */
    override fun next(timeout: Long): Pair<Long, Long>? {
//        NVDrawVulkanImage.glSignalVkSemaphoreNV(-1L)
//        return (presentedFrames % 2) to -1L//.toInt()
        MemoryStack.stackPush().use { stack ->
            VK10.vkQueueWaitIdle(queue)

            val signal = stack.mallocLong(1)
            signal.put(0, imageAvailableSemaphores[currentImage])

            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                endCommandBuffer(this@OpenGLSwapchain.device, commandPools.Standard, queue,
                    flush = true, dealloc = true, fence = imageUseFences[currentImage], signalSemaphores = signal)
            }
        }

        return imageAvailableSemaphores[currentImage] to imageUseFences[currentImage]
    }

    /**
     * Toggles fullscreen.
     */
    override fun toggleFullscreen(hub: Hub, swapchainRecreator: VulkanRenderer.SwapchainRecreator) {
        val window = this.window

        if(window !is SceneryWindow.GLFWWindow) {
            throw IllegalStateException("Cannot use a window of type ${window.javaClass.simpleName}")
        }

        if (window.isFullscreen) {
            glfwSetWindowMonitor(window.window,
                NULL,
                0, 0,
                window.width, window.height, GLFW_DONT_CARE)
            glfwSetWindowPos(window.window, 100, 100)
            glfwSetInputMode(window.window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)

            swapchainRecreator.mustRecreate = true
            window.isFullscreen = false
        } else {
            val preferredMonitor = System.getProperty("scenery.FullscreenMonitor", "0").toInt()

            val monitor = if (preferredMonitor == 0) {
                glfwGetPrimaryMonitor()
            } else {
                val monitors = glfwGetMonitors()
                if (monitors != null && monitors.remaining() >= preferredMonitor) {
                    monitors.get(preferredMonitor)
                } else {
                    glfwGetPrimaryMonitor()
                }
            }

            val hmd = hub.getWorkingHMDDisplay()

            if (hmd != null) {
                window.width = hmd.getRenderTargetSize().x() / 2
                window.height = hmd.getRenderTargetSize().y()
                logger.info("Set fullscreen window dimensions to ${window.width}x${window.height}")
            }

            glfwSetWindowMonitor(window.window,
                monitor,
                0, 0,
                window.width, window.height, GLFW_DONT_CARE)
            glfwSetInputMode(window.window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN)

            swapchainRecreator.mustRecreate = true
            window.isFullscreen = true
        }
    }

    /**
     * Embeds the swapchain into a [SceneryFXPanel]. Not supported by [OpenGLSwapchain], see [FXSwapchain] instead.
     */
    override fun embedIn(panel: SceneryPanel?) {
        if(panel != null) {
            logger.error("Embedding is not supported with the OpenGL-based swapchain. Use FXSwapchain instead.")
        }
    }

    /**
     * Returns the number of presented frames.
     */
    override fun presentedFrames(): Long {
        return presentedFrames
    }

    /**
     * Closes the swapchain, freeing all of its resources.
     */
    override fun close() {
        val window = this.window

        if(window !is SceneryWindow.GLFWWindow) {
            throw IllegalStateException("Cannot use a window of type ${window.javaClass.simpleName}")
        }

        vkQueueWaitIdle(queue)

        closeSyncPrimitives()

        windowSizeCallback.close()
        glfwDestroyWindow(window.window)
    }

    companion object: SwapchainParameters {
        override var headless = false
        override var usageCondition = { _: SceneryPanel? -> java.lang.Boolean.parseBoolean(System.getProperty("scenery.VulkanRenderer.UseOpenGLSwapchain", "false")) }
    }
}