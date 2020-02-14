package graphics.scenery.backends.vulkan

import cleargl.GLMatrix
import cleargl.GLVector
import com.fasterxml.jackson.module.kotlin.isKotlinClass
import graphics.scenery.*
import graphics.scenery.backends.*
import graphics.scenery.spirvcrossj.Loader
import graphics.scenery.spirvcrossj.libspirvcrossj
import graphics.scenery.utils.*
import kotlinx.coroutines.*
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Platform
import org.lwjgl.system.jemalloc.JEmalloc.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugReport.*
import org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
import org.lwjgl.vulkan.KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.KHRXlibSurface.VK_KHR_XLIB_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.MVKMacosSurface.VK_MVK_MACOS_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.reflections.Reflections
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.reflect.full.*


/**
 * Vulkan Renderer
 *
 * @param[hub] Hub instance to use and attach to.
 * @param[applicationName] The name of this application.
 * @param[scene] The [Scene] instance to initialize first.
 * @param[windowWidth] Horizontal window size.
 * @param[windowHeight] Vertical window size.
 * @param[embedIn] An optional [SceneryPanel] in which to embed the renderer instance.
 * @param[renderConfigFile] The file to create a [RenderConfigReader.RenderConfig] from.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

@Suppress("MemberVisibilityCanBePrivate")
open class VulkanRenderer(hub: Hub,
                          applicationName: String,
                          scene: Scene,
                          windowWidth: Int,
                          windowHeight: Int,
                          final override var embedIn: SceneryPanel? = null,
                          renderConfigFile: String) : Renderer(), AutoCloseable {

    protected val logger by LazyLogger()

    // helper classes
    data class PresentHelpers(
        var signalSemaphore: LongBuffer = memAllocLong(1),
        var waitSemaphore: LongBuffer = memAllocLong(1),
        var commandBuffers: PointerBuffer = memAllocPointer(1),
        var waitStages: IntBuffer = memAllocInt(1),
        var submitInfo: VkSubmitInfo = VkSubmitInfo.calloc()
    )

    enum class VertexDataKinds {
        None,
        PositionNormalTexcoord,
        PositionTexcoords,
        PositionNormal
    }

    enum class StandardSemaphores {
        RenderComplete,
        ImageAvailable,
        PresentComplete
    }

    data class VertexDescription(
        val state: VkPipelineVertexInputStateCreateInfo,
        val attributeDescription: VkVertexInputAttributeDescription.Buffer?,
        val bindingDescription: VkVertexInputBindingDescription.Buffer?
    )

    data class CommandPools(
        var Standard: Long = -1L,
        var Render: Long = -1L,
        var Compute: Long = -1L,
        var Transfer: Long = -1L
    )

    data class DeviceAndGraphicsQueueFamily(
        val device: VkDevice? = null,
        val graphicsQueue: Int = 0,
        val computeQueue: Int = 0,
        val presentQueue: Int = 0,
        val transferQueue: Int = 0,
        val memoryProperties: VkPhysicalDeviceMemoryProperties? = null
    )

    class Pipeline {
        internal var pipeline: Long = 0
        internal var layout: Long = 0
    }

    sealed class DescriptorSet(val id: Long = 0L, val name: String = "") {
        object None: DescriptorSet(0L)
        data class Set(val setId: Long, val setName: String = "") : DescriptorSet(setId, setName)
        data class DynamicSet(val setId: Long, val offset: Int, val setName: String = "") : DescriptorSet(setId, setName)

        companion object {
            fun setOrNull(id: Long?, setName: String): DescriptorSet? {
                return if(id == null) {
                    null
                } else {
                    DescriptorSet.Set(id, setName)
                }
            }
        }
    }

    private val lateResizeInitializers = ConcurrentHashMap<Node, () -> Any>()

    inner class SwapchainRecreator {
        var mustRecreate = true
        var afterRecreateHook: (SwapchainRecreator) -> Unit = {}

        private val lock = ReentrantLock()

        @Synchronized fun recreate() {
            if(lock.tryLock() && !shouldClose) {
                logger.info("Recreating Swapchain at frame $frames (${swapchain.javaClass.simpleName})")
                // create new swapchain with changed surface parameters
                vkQueueWaitIdle(queue)

                with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                    // Create the swapchain (this will also add a memory barrier to initialize the framebuffer images)

                    swapchain.create(oldSwapchain = swapchain)

                    endCommandBuffer(this@VulkanRenderer.device, commandPools.Standard, queue, flush = true, dealloc = true)

                    this
                }

                val pipelineCacheInfo = VkPipelineCacheCreateInfo.calloc()
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO)
                    .pNext(NULL)
                    .flags(VK_FLAGS_NONE)

                val refreshResolutionDependentResources = {
                    if (pipelineCache != -1L) {
                        vkDestroyPipelineCache(device.vulkanDevice, pipelineCache, null)
                    }

                    pipelineCache = VU.getLong("create pipeline cache",
                        { vkCreatePipelineCache(device.vulkanDevice, pipelineCacheInfo, null, this) },
                        { pipelineCacheInfo.free() })

                    renderpasses.values.forEach { it.close() }
                    renderpasses.clear()

                    settings.set("Renderer.displayWidth", (window.width * settings.get<Float>("Renderer.SupersamplingFactor")).toInt())
                    settings.set("Renderer.displayHeight", (window.height * settings.get<Float>("Renderer.SupersamplingFactor")).toInt())

                    prepareRenderpassesFromConfig(renderConfig, window.width, window.height)

                    semaphores.forEach { it.value.forEach { semaphore -> vkDestroySemaphore(device.vulkanDevice, semaphore, null) } }
                    semaphores = prepareStandardSemaphores(device)

                    // Create render command buffers
                    vkResetCommandPool(device.vulkanDevice, commandPools.Render, VK_FLAGS_NONE)

                    scene.findObserver()?.let { cam ->
                        cam.perspectiveCamera(cam.fov, window.width.toFloat(), window.height.toFloat(), cam.nearPlaneDistance, cam.farPlaneDistance)
                    }

                    logger.debug("Calling late resize initializers for ${lateResizeInitializers.keys.joinToString(", ")}")
                    lateResizeInitializers.map { it.value.invoke() }

                    if (timestampQueryPool != -1L) {
                        vkDestroyQueryPool(device.vulkanDevice, timestampQueryPool, null)
                    }

                    val queryPoolCreateInfo = VkQueryPoolCreateInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                        .pNext(NULL)
                        .queryType(VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(renderConfig.renderpasses.size * 2)

                    timestampQueryPool = VU.getLong("Create timestamp query pool",
                        { vkCreateQueryPool(device.vulkanDevice, queryPoolCreateInfo, null, this) },
                        { queryPoolCreateInfo.free() })
                }

                refreshResolutionDependentResources.invoke()

                totalFrames = 0
                mustRecreate = false

                afterRecreateHook.invoke(this)

                lock.unlock()
            }
        }
    }

    /** Debug callback to be used upon encountering validation messages or errors */
    var debugCallback = object : VkDebugReportCallbackEXT() {
        override operator fun invoke(flags: Int, objectType: Int, obj: Long, location: Long, messageCode: Int, pLayerPrefix: Long, pMessage: Long, pUserData: Long): Int {
            val dbg = if (flags and VK_DEBUG_REPORT_DEBUG_BIT_EXT == VK_DEBUG_REPORT_DEBUG_BIT_EXT) {
                " (debug)"
            } else {
                ""
            }

            when {
                flags and VK_DEBUG_REPORT_ERROR_BIT_EXT == VK_DEBUG_REPORT_ERROR_BIT_EXT ->
                    logger.error("!! $obj($objectType) Validation$dbg: " + getString(pMessage))
                flags and VK_DEBUG_REPORT_WARNING_BIT_EXT == VK_DEBUG_REPORT_WARNING_BIT_EXT ->
                    logger.warn("!! $obj($objectType) Validation$dbg: " + getString(pMessage))
                flags and VK_DEBUG_REPORT_PERFORMANCE_WARNING_BIT_EXT == VK_DEBUG_REPORT_PERFORMANCE_WARNING_BIT_EXT ->
                    logger.error("!! $obj($objectType) Validation (performance)$dbg: " + getString(pMessage))
                flags and VK_DEBUG_REPORT_INFORMATION_BIT_EXT == VK_DEBUG_REPORT_INFORMATION_BIT_EXT ->
                    logger.info("!! $obj($objectType) Validation$dbg: " + getString(pMessage))
                else ->
                    logger.info("!! $obj($objectType) Validation (unknown message type)$dbg: " + getString(pMessage))
            }

            // trigger exception and delay if strictValidation is activated in general, or only for specific object types
            if(strictValidation.first && strictValidation.second.isEmpty() ||
                strictValidation.first && strictValidation.second.contains(objectType)) {
                // set 15s of delay until the next frame is rendered if a validation error happens
                renderDelay = 1500L

                try {
                    throw Exception("Vulkan validation layer exception, see validation layer error messages above. To disable these exceptions, set scenery.VulkanRenderer.StrictValidation=false. Stack trace:")
                } catch (e: Exception) {
                    logger.error(e.message)
                    e.printStackTrace()
                }
            }

            // return false here, otherwise the application would quit upon encountering a validation error.
            return VK_FALSE
        }
    }

    // helper classes end



    final override var hub: Hub? = null
    protected var applicationName = ""
    final override var settings: Settings = Settings()
    override var shouldClose = false
    private var toggleFullscreen = false
    override var managesRenderLoop = false
    override var lastFrameTime = System.nanoTime() * 1.0f
    final override var initialized = false
    override var firstImageReady: Boolean = false

    private var screenshotRequested = false
    private var screenshotOverwriteExisting = false
    private var screenshotFilename = ""
    var screenshotBuffer: VulkanBuffer? = null
    var imageBuffer: ByteBuffer? = null
    var encoder: H264Encoder? = null
    private var movieFilename = ""
    var recordMovie: Boolean = false
    override var pushMode: Boolean = false

    private var firstWaitSemaphore: LongBuffer = memAllocLong(1)

    var scene: Scene = Scene()
    protected var sceneArray: HashSet<Node> = HashSet(256)

    protected var commandPools = CommandPools()
    protected val renderpasses: MutableMap<String, VulkanRenderpass> = Collections.synchronizedMap(LinkedHashMap<String, VulkanRenderpass>())

    protected var validation = java.lang.Boolean.parseBoolean(System.getProperty("scenery.VulkanRenderer.EnableValidations", "false"))
    protected val strictValidation = getStrictValidation()
    protected val wantsOpenGLSwapchain = java.lang.Boolean.parseBoolean(System.getProperty("scenery.VulkanRenderer.UseOpenGLSwapchain", "false"))
    protected val defaultValidationLayers = arrayOf("VK_LAYER_LUNARG_standard_validation")

    protected var instance: VkInstance
    protected var device: VulkanDevice
    /** list of all vulkan devices discovered during VulkanDevice.fromPhysicalDevice() */
    val discoveredDevices = ArrayList<String>(10)

    protected var debugCallbackHandle: Long = -1L
    protected var timestampQueryPool: Long = -1L

    protected var semaphoreCreateInfo: VkSemaphoreCreateInfo

    // Create static Vulkan resources
    protected var queue: VkQueue
    protected var transferQueue: VkQueue
    protected var descriptorPool: Long

    protected var swapchain: Swapchain
    protected var ph = PresentHelpers()

    final override var window: SceneryWindow = SceneryWindow.UninitializedWindow()

    protected val swapchainRecreator: SwapchainRecreator
    protected var pipelineCache: Long = -1L
    protected var vertexDescriptors = ConcurrentHashMap<VertexDataKinds, VertexDescription>()
    protected var sceneUBOs = ArrayList<Node>()
    protected var geometryPool: VulkanBufferPool
    protected var semaphores = ConcurrentHashMap<StandardSemaphores, Array<Long>>()

    data class DefaultBuffers(var UBOs: VulkanBuffer,
                              var LightParameters: VulkanBuffer,
                              var VRParameters: VulkanBuffer,
                              var ShaderProperties: VulkanBuffer)
    protected var buffers: DefaultBuffers
    protected var defaultUBOs = ConcurrentHashMap<String, VulkanUBO>()
    protected var textureCache = ConcurrentHashMap<String, VulkanTexture>()
    protected var descriptorSetLayouts = ConcurrentHashMap<String, Long>()
    protected var descriptorSets = ConcurrentHashMap<String, Long>()

    protected var lastTime = System.nanoTime()
    protected var time = 0.0f
    var fps = 0
        protected set
    protected var frames = 0
    protected var totalFrames = 0L
    protected var renderDelay = 0L
    protected var heartbeatTimer = Timer()
    protected var gpuStats: GPUStats? = null

    private var renderConfig: RenderConfigReader.RenderConfig
    private var flow: List<String> = listOf()

    private val vulkanProjectionFix =
        GLMatrix(floatArrayOf(
            1.0f,  0.0f, 0.0f, 0.0f,
            0.0f, -1.0f, 0.0f, 0.0f,
            0.0f,  0.0f, 0.5f, 0.0f,
            0.0f,  0.0f, 0.5f, 1.0f))

    final override var renderConfigFile: String = ""
        set(config) {
            field = config

            this.renderConfig = RenderConfigReader().loadFromFile(renderConfigFile)

            // check for null as this is used in the constructor as well where
            // the swapchain recreator is not yet initialized
            @Suppress("SENSELESS_COMPARISON")
            if (swapchainRecreator != null) {
                swapchainRecreator.mustRecreate = true
                logger.info("Loaded ${renderConfig.name} (${renderConfig.description ?: "no description"})")
            }
        }

    companion object {
        private const val VK_FLAGS_NONE: Int = 0
        private const val MAX_TEXTURES = 2048 * 16
        private const val MAX_UBOS = 2048
        private const val MAX_INPUT_ATTACHMENTS = 32
        private const val UINT64_MAX: Long = -1L

        private const val MATERIAL_HAS_DIFFUSE = 0x0001
        private const val MATERIAL_HAS_AMBIENT = 0x0002
        private const val MATERIAL_HAS_SPECULAR = 0x0004
        private const val MATERIAL_HAS_NORMAL = 0x0008
        private const val MATERIAL_HAS_ALPHAMASK = 0x0010

        init {
            Loader.loadNatives()
            libspirvcrossj.initializeProcess()

            Runtime.getRuntime().addShutdownHook(object: Thread() {
                override fun run() {
                    logger.debug("Finalizing libspirvcrossj")
                    libspirvcrossj.finalizeProcess()
                }
            })
        }

        fun getStrictValidation(): Pair<Boolean, List<Int>> {
            val strict = System.getProperty("scenery.VulkanRenderer.StrictValidation")
            val separated = strict?.split(",")?.asSequence()?.mapNotNull { it.toIntOrNull() }?.toList()

            return when {
                strict == null -> false to emptyList()
                strict == "true" -> true to emptyList()
                strict == "false" -> false to emptyList()
                separated != null && separated.count() > 0 -> true to separated
                else -> false to emptyList()
            }
        }
    }

    init {
        this.hub = hub

        val hmd = hub.getWorkingHMDDisplay()
        if (hmd != null) {
            logger.debug("Setting window dimensions to bounds from HMD")
            val bounds = hmd.getRenderTargetSize()
            window.width = bounds.x().toInt() * 2
            window.height = bounds.y().toInt()
        } else {
            window.width = windowWidth
            window.height = windowHeight
        }

        this.applicationName = applicationName
        this.scene = scene

        this.settings = loadDefaultRendererSettings((hub.get(SceneryElement.Settings) as Settings))

        logger.debug("Loading rendering config from $renderConfigFile")
        this.renderConfigFile = renderConfigFile
        this.renderConfig = RenderConfigReader().loadFromFile(renderConfigFile)

        logger.info("Loaded ${renderConfig.name} (${renderConfig.description ?: "no description"})")

        if((System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE")?.toInt() == 1  || Renderdoc.renderdocAttached)&& validation) {
            logger.warn("Validation Layers requested, but Renderdoc capture and Validation Layers are mutually incompatible. Disabling validations layers.")
            validation = false
        }

        // explicitly create VK, to make GLFW pick up MoltenVK on OS X
        if(ExtractsNatives.getPlatform() == ExtractsNatives.Platform.MACOS) {
            try {
                Configuration.VULKAN_EXPLICIT_INIT.set(true)
                VK.create()
            } catch (e: IllegalStateException) {
                logger.warn("IllegalStateException during Vulkan initialisation")
            }
        }


        // Create the Vulkan instance
        instance = if(embedIn != null) {
            createInstance(null)
        } else {
            if (!glfwInit()) {
                throw RuntimeException("Failed to initialize GLFW")
            }
            if (!glfwVulkanSupported()) {
                throw UnsupportedOperationException("Failed to find Vulkan loader. Is Vulkan supported by your GPU and do you have the most recent graphics drivers installed?")
            }

            /* Look for instance extensions */
            val requiredExtensions = glfwGetRequiredInstanceExtensions() ?: throw RuntimeException("Failed to find list of required Vulkan extensions")
            createInstance(requiredExtensions)
        }

        debugCallbackHandle = if(validation) {
            setupDebugging(instance,
                VK_DEBUG_REPORT_ERROR_BIT_EXT or VK_DEBUG_REPORT_WARNING_BIT_EXT,
                debugCallback)
        } else {
            -1L
        }

        val requestedValidationLayers = if(validation) {
            if(wantsOpenGLSwapchain) {
                logger.warn("Requested OpenGL swapchain, validation layers disabled.")
                emptyArray()
            } else {
                defaultValidationLayers
            }
        } else {
            emptyArray()
        }

        // get available swapchains, but remove default swapchain, will always be there as fallback
        val swapchains = Reflections("graphics.scenery.backends.vulkan").getSubTypesOf(Swapchain::class.java).filter { it.isKotlinClass() && it.kotlin.companionObject != null && it.simpleName != "VulkanSwapchain" }

        logger.debug("Available special-purpose swapchains are: ${swapchains.joinToString { it.simpleName }}")
        val selectedSwapchain = swapchains.firstOrNull { (it.kotlin.companionObjectInstance as SwapchainParameters).usageCondition.invoke(embedIn) }
        val headless = (selectedSwapchain?.kotlin?.companionObjectInstance as? SwapchainParameters)?.headless ?: false

        logger.debug("Will be matching device against: "+System.getProperty("scenery.Renderer.Device"))
        device = VulkanDevice.fromPhysicalDevice(instance,
            physicalDeviceFilter = { _, device -> "${device.vendor} ${device.name}".contains(System.getProperty("scenery.Renderer.Device", "DOES_NOT_EXIST"))},
            additionalExtensions = { physicalDevice -> hub.getWorkingHMDDisplay()?.getVulkanDeviceExtensions(physicalDevice)?.toTypedArray() ?: arrayOf() },
            validationLayers = requestedValidationLayers,
            discoveredDevices = discoveredDevices,
            headless = headless)

        logger.debug("Device creation done")

        if(device.deviceData.vendor.toLowerCase().contains("nvidia") && ExtractsNatives.getPlatform() == ExtractsNatives.Platform.WINDOWS) {
            try {
                gpuStats = NvidiaGPUStats()
            } catch(e: NullPointerException) {
                logger.warn("Could not initialize Nvidia GPU stats")
                if(logger.isDebugEnabled) {
                    logger.warn("Reason: ${e.message}, traceback follows:")
                    e.printStackTrace()
                }
            }
        }

        queue = VU.createDeviceQueue(device, device.queueIndices.graphicsQueue)
        logger.debug("Creating transfer queue with ${device.queueIndices.transferQueue} (vs ${device.queueIndices.graphicsQueue})")
        transferQueue = VU.createDeviceQueue(device, device.queueIndices.transferQueue)

        with(commandPools) {
            Render = device.createCommandPool(device.queueIndices.graphicsQueue)
            Standard = device.createCommandPool(device.queueIndices.graphicsQueue)
            Compute = device.createCommandPool(device.queueIndices.computeQueue)
            Transfer = device.createCommandPool(device.queueIndices.transferQueue)
        }
        logger.debug("Creating command pools done")

        swapchainRecreator = SwapchainRecreator()
        swapchain = when {
            selectedSwapchain != null -> {
                logger.info("Using swapchain ${selectedSwapchain.simpleName}")
                val params = selectedSwapchain.kotlin.primaryConstructor!!.parameters.associate { param ->
                    param to when(param.name) {
                        "device" -> device
                        "queue" -> queue
                        "commandPools" -> commandPools
                        "renderConfig" -> renderConfig
                        "useSRGB" -> renderConfig.sRGB
                        else -> null
                    }
                }.filter { it.value != null }

                selectedSwapchain
                    .kotlin
                    .primaryConstructor!!
                    .callBy(params) as Swapchain
            }
            else -> {
                logger.info("Using default swapchain")
                VulkanSwapchain(
                    device, queue, commandPools,
                    renderConfig = renderConfig, useSRGB = renderConfig.sRGB,
                    vsync = !settings.get<Boolean>("Renderer.DisableVsync"),
                    undecorated = settings.get("Renderer.ForceUndecoratedWindow"))
            }
        }.apply {
            embedIn(embedIn)
            window = createWindow(window, swapchainRecreator)
        }

        logger.debug("Created swapchain")
        vertexDescriptors = prepareStandardVertexDescriptors()
        logger.debug("Created vertex descriptors")
        descriptorPool = createDescriptorPool(device)
        logger.debug("Created descriptor pool")

        descriptorSetLayouts = prepareDefaultDescriptorSetLayouts(device)
        logger.debug("Prepared default DSLs")
        buffers = prepareDefaultBuffers(device)
        logger.debug("Prepared default buffers")

        prepareDescriptorSets(device, descriptorPool)
        logger.debug("Prepared default descriptor sets")
        prepareDefaultTextures(device)
        logger.debug("Prepared default textures")

        heartbeatTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (window.shouldClose) {
                    shouldClose = true
                    return
                }

                fps = frames
                frames = 0

                if(!pushMode) {
                    (hub.get(SceneryElement.Statistics) as? Statistics)?.add("Renderer.fps", fps, false)
                }

                gpuStats?.let {
                    it.update(0)

                    hub.get(SceneryElement.Statistics).let { s ->
                        val stats = s as Statistics

                        stats.add("GPU", it.get("GPU"), isTime = false)
                        stats.add("GPU bus", it.get("Bus"), isTime = false)
                        stats.add("GPU mem", it.get("AvailableDedicatedVideoMemory"), isTime = false)
                    }

                    if (settings.get("Renderer.PrintGPUStats")) {
                        logger.info(it.utilisationToString())
                        logger.info(it.memoryUtilisationToString())
                    }
                }

                val validationsEnabled = if (validation) {
                    " - VALIDATIONS ENABLED"
                } else {
                    ""
                }

                if(embedIn == null) {
                    window.title = "$applicationName [${this@VulkanRenderer.javaClass.simpleName}, ${this@VulkanRenderer.renderConfig.name}] $validationsEnabled - $fps fps"
                }
            }
        }, 0, 1000)

        // Info struct to create a semaphore
        semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
            .pNext(NULL)
            .flags(0)

        lastTime = System.nanoTime()
        time = 0.0f

        if(System.getProperty("scenery.RunFullscreen","false")?.toBoolean() == true) {
            toggleFullscreen = true
        }

        geometryPool = VulkanBufferPool(device, usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT or VK_BUFFER_USAGE_INDEX_BUFFER_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT)

        initialized = true
        logger.info("Renderer initialisation complete.")
    }

    // source: http://stackoverflow.com/questions/34697828/parallel-operations-on-kotlin-collections
    // Thanks to Holger :-)
    @Suppress("UNUSED")
    fun <T, R> Iterable<T>.parallelMap(
        numThreads: Int = Runtime.getRuntime().availableProcessors(),
        exec: ExecutorService = Executors.newFixedThreadPool(numThreads),
        transform: (T) -> R): List<R> {

        // default size is just an inlined version of kotlin.collections.collectionSizeOrDefault
        val defaultSize = if (this is Collection<*>) this.size else 10
        val destination = Collections.synchronizedList(ArrayList<R>(defaultSize))

        for (item in this) {
            exec.submit { destination.add(transform(item)) }
        }

        exec.shutdown()
        exec.awaitTermination(1, TimeUnit.DAYS)

        return ArrayList<R>(destination)
    }

    @Suppress("UNUSED")
    fun setCurrentScene(scene: Scene) {
        this.scene = scene
    }

    /**
     * This function should initialize the current scene contents.
     */
    override fun initializeScene() {
        logger.info("Scene initialization started.")

        this.scene.discover(this.scene, { it is HasGeometry && it !is Light })
//            .parallelMap(numThreads = System.getProperty("scenery.MaxInitThreads", "1").toInt()) { node ->
            .map { node ->
                // skip initialization for nodes that are only instance slaves
                logger.debug("Initializing object '${node.name}'")
                node.metadata["VulkanRenderer"] = VulkanObjectState()

                initializeNode(node)
            }

        scene.initialized = true
        logger.info("Scene initialization complete.")
    }

    fun Boolean.toInt(): Int {
        return if (this) {
            1
        } else {
            0
        }
    }

    fun updateNodeGeometry(node: Node) {
        if (node is HasGeometry && node.vertices.remaining() > 0) {
            node.rendererMetadata()?.let { s ->
                createVertexBuffers(device, node, s)
            }
        }
    }

    /**
     * Returns the material type flag for a Node, considering it's [Material]'s textures.
     */
    protected fun Node.materialTypeFromTextures(s: VulkanObjectState): Int {
        var materialType = 0
        if (material.textures.containsKey("ambient") && !s.defaultTexturesFor.contains("ambient")) {
            materialType = materialType or MATERIAL_HAS_AMBIENT
        }

        if (material.textures.containsKey("diffuse") && !s.defaultTexturesFor.contains("diffuse")) {
            materialType = materialType or MATERIAL_HAS_DIFFUSE
        }

        if (material.textures.containsKey("specular") && !s.defaultTexturesFor.contains("specular")) {
            materialType = materialType or MATERIAL_HAS_SPECULAR
        }

        if (material.textures.containsKey("normal") && !s.defaultTexturesFor.contains("normal")) {
            materialType = materialType or MATERIAL_HAS_NORMAL
        }

        if (material.textures.containsKey("alphamask") && !s.defaultTexturesFor.contains("alphamask")) {
            materialType = materialType or MATERIAL_HAS_ALPHAMASK
        }

        return materialType
    }

    /**
     * Initialises a given [node] with the metadata required by the [VulkanRenderer].
     */
    fun initializeNode(node: Node): Boolean {
        var s: VulkanObjectState = node.rendererMetadata() ?: throw IllegalStateException("Node ${node.name} does not contain metadata object")

        if (s.initialized) return true

        logger.debug("Initializing ${node.name} (${(node as HasGeometry).vertices.remaining() / node.vertexSize} vertices/${node.indices.remaining()} indices)")

        // determine vertex input type
        s.vertexInputType = when {
            node.vertices.remaining() > 0 && node.normals.remaining() > 0 && node.texcoords.remaining() > 0 -> VertexDataKinds.PositionNormalTexcoord
            node.vertices.remaining() > 0 && node.normals.remaining() > 0 && node.texcoords.remaining() == 0 -> VertexDataKinds.PositionNormal
            node.vertices.remaining() > 0 && node.normals.remaining() == 0 && node.texcoords.remaining() > 0 -> VertexDataKinds.PositionTexcoords
            else -> VertexDataKinds.PositionNormalTexcoord
        }

        // create custom vertex description if necessary, else use one of the defaults
        s.vertexDescription = if (node.instances.size > 0 || node.instancedProperties.size > 0) {
            updateInstanceBuffer(device, node, s)
            // TODO: Rewrite shader in case it does not conform to coord/normal/texcoord vertex description
            s.vertexInputType = VertexDataKinds.PositionNormalTexcoord
            s.instanced = true
            vertexDescriptionFromInstancedNode(node, vertexDescriptors.getValue(VertexDataKinds.PositionNormalTexcoord))
        } else {
            s.instanced = false
            s.vertexBuffers["instance"]?.close()
            s.vertexBuffers.remove("instance")
            s.vertexBuffers["instanceStaging"]?.close()
            s.vertexBuffers.remove("instanceStaging")
            vertexDescriptors.getValue(s.vertexInputType)
        }

        s = createVertexBuffers(device, node, s)

        val matricesDescriptorSet = getDescriptorCache().getOrPut("Matrices") {
            VU.createDescriptorSetDynamic(device, descriptorPool,
                descriptorSetLayouts["Matrices"]!!, 1,
                buffers.UBOs)
        }

        val materialPropertiesDescriptorSet = getDescriptorCache().getOrPut("MaterialProperties") {
            VU.createDescriptorSetDynamic(device, descriptorPool,
                descriptorSetLayouts["MaterialProperties"]!!, 1,
                buffers.UBOs)
        }

        val matricesUbo = VulkanUBO(device, backingBuffer = buffers.UBOs)
        with(matricesUbo) {
            name = "Matrices"
            add("ModelMatrix", { node.world })
            add("NormalMatrix", { node.world.inverse.transpose() })
            add("isBillboard", { node.isBillboard.toInt() })

            createUniformBuffer()
            sceneUBOs.add(node)

            s.UBOs.put(name, matricesDescriptorSet to this)
        }

        try {
            initializeCustomShadersForNode(node)
        } catch (e: ShaderCompilationException) {
            logger.error("Compilation of custom shader failed: ${e.message}")
            logger.error("Node ${node.name} will use default shader for render pass.")

            if (logger.isDebugEnabled) {
                e.printStackTrace()
            }
        }

        loadTexturesForNode(node, s)

        s.materialHashCode = node.material.materialHashCode()

        val materialUbo = VulkanUBO(device, backingBuffer = buffers.UBOs)
        with(materialUbo) {
            name = "MaterialProperties"
            add("materialType", { node.materialTypeFromTextures(s) })
            add("Ka", { node.material.ambient })
            add("Kd", { node.material.diffuse })
            add("Ks", { node.material.specular })
            add("Roughness", { node.material.roughness})
            add("Metallic", { node.material.metallic})
            add("Opacity", { node.material.blending.opacity })

            createUniformBuffer()
            s.UBOs.put("MaterialProperties", materialPropertiesDescriptorSet to this)
        }

        s.initialized = true
        node.initialized = true
        node.metadata["VulkanRenderer"] = s

        return true
    }

    private fun initializeCustomShadersForNode(node: Node, addInitializer: Boolean = true): Boolean {

        if(!(node.material.blending.transparent || node.material is ShaderMaterial || node.material.cullingMode != Material.CullingMode.Back || node.material.wireframe)) {
            logger.debug("Using default renderpass material for ${node.name}")
            renderpasses
                .filter { it.value.passConfig.type == RenderConfigReader.RenderpassType.geometry || it.value.passConfig.type == RenderConfigReader.RenderpassType.lights }
                .forEach {
                    it.value.removePipeline(node)
                }

            lateResizeInitializers.remove(node)
            return false
        }

        if(addInitializer) {
            lateResizeInitializers.remove(node)
        }

        node.rendererMetadata()?.let { s ->

//            node.javaClass.kotlin.memberProperties.filter { it.findAnnotation<ShaderProperty>() != null }.forEach { logger.info("${node.name}.${it.name} is ShaderProperty!") }
            val needsShaderPropertyUBO = if (node.javaClass.kotlin.memberProperties.filter { it.findAnnotation<ShaderProperty>() != null }.count() > 0) {
                var dsl = 0L

                renderpasses.filter {
                    (it.value.passConfig.type == RenderConfigReader.RenderpassType.geometry || it.value.passConfig.type == RenderConfigReader.RenderpassType.lights)
                        && it.value.passConfig.renderTransparent == node.material.blending.transparent
                }
                    .map { pass ->
                        logger.debug("Initializing shader properties for ${node.name}")
                        dsl = pass.value.initializeShaderPropertyDescriptorSetLayout()
                    }

                val descriptorSet = VU.createDescriptorSetDynamic(device, descriptorPool, dsl,
                    1, buffers.ShaderProperties)

                s.requiredDescriptorSets["ShaderProperties"] = descriptorSet
                true
            } else {
                false
            }


            renderpasses.filter { it.value.passConfig.type == RenderConfigReader.RenderpassType.geometry || it.value.passConfig.type == RenderConfigReader.RenderpassType.lights }
                .map { pass ->
                    val shaders = when {
                        node.material is ShaderMaterial -> {
                            logger.debug("Initializing preferred pipeline for ${node.name} from ShaderMaterial")
                            (node.material as ShaderMaterial).shaders
                        }

//                        pass.value.passConfig.renderTransparent == node.material.blending.transparent -> {
//                            logger.debug("Initializing classname-derived preferred pipeline for ${node.name}")
//                            val shaders = node.findExistingShaders()
//
//                            if(shaders.isEmpty()) {
//                                throw ShaderCompilationException("No shaders found for ${node.name}")
//                            }
//
//                            shaders
//                        }

                        else -> {
                            logger.debug("Initializing pass-default shader preferred pipeline for ${node.name}")
                            Shaders.ShadersFromFiles(pass.value.passConfig.shaders.map { "shaders/$it" }.toTypedArray())
                        }
                    }

                    logger.debug("Shaders are: $shaders")

                    val shaderModules = ShaderType.values().mapNotNull { type ->
                        try {
                            VulkanShaderModule.getFromCacheOrCreate(device, "main", shaders.get(Shaders.ShaderTarget.Vulkan, type))
                        } catch (e: ShaderNotFoundException) {
                            null
                        } catch (e: ShaderConsistencyException) {
                            logger.warn("${e.message} - Falling back to default shader.")
                            if(logger.isDebugEnabled) {
                                e.printStackTrace()
                            }
                            return false
                        }
                    }

                    pass.value.initializeInputAttachmentDescriptorSetLayouts(shaderModules)
                    pass.value.initializePipeline("preferred-${node.uuid}",
                        shaderModules, settings = { pipeline ->
                            when(node.material.cullingMode) {
                                Material.CullingMode.None -> pipeline.rasterizationState.cullMode(VK_CULL_MODE_NONE)
                                Material.CullingMode.Front -> pipeline.rasterizationState.cullMode(VK_CULL_MODE_FRONT_BIT)
                                Material.CullingMode.Back -> pipeline.rasterizationState.cullMode(VK_CULL_MODE_BACK_BIT)
                                Material.CullingMode.FrontAndBack -> pipeline.rasterizationState.cullMode(VK_CULL_MODE_FRONT_AND_BACK)
                            }

                            when(node.material.depthTest) {
                                Material.DepthTest.Equal -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_EQUAL)
                                Material.DepthTest.Less -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_LESS)
                                Material.DepthTest.Greater -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_GREATER)
                                Material.DepthTest.LessEqual -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                                Material.DepthTest.GreaterEqual -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_GREATER_OR_EQUAL)
                                Material.DepthTest.Always -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_ALWAYS)
                                Material.DepthTest.Never -> pipeline.depthStencilState.depthCompareOp(VK_COMPARE_OP_NEVER)
                            }

                            if(node.material.wireframe) {
                                pipeline.rasterizationState.polygonMode(VK_POLYGON_MODE_LINE)
                            } else {
                                pipeline.rasterizationState.polygonMode(VK_POLYGON_MODE_FILL)
                            }

                            if(node.material.blending.transparent) {
                                with(node.material.blending) {
                                    val blendStates = pipeline.colorBlendState.pAttachments()
                                    for (attachment in 0 until (blendStates?.capacity() ?: 0)) {
                                        val state = blendStates?.get(attachment)

                                        @Suppress("SENSELESS_COMPARISON", "IfThenToSafeAccess")
                                        if (state != null) {
                                            state.blendEnable(true)
                                                .colorBlendOp(colorBlending.toVulkan())
                                                .srcColorBlendFactor(sourceColorBlendFactor.toVulkan())
                                                .dstColorBlendFactor(destinationColorBlendFactor.toVulkan())
                                                .alphaBlendOp(alphaBlending.toVulkan())
                                                .srcAlphaBlendFactor(sourceAlphaBlendFactor.toVulkan())
                                                .dstAlphaBlendFactor(destinationAlphaBlendFactor.toVulkan())
                                                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                                        }
                                    }
                                }
                            }
                        },
                        vertexInputType = s.vertexDescription!!)
                }


            if (needsShaderPropertyUBO) {
                renderpasses.filter {
                    (it.value.passConfig.type == RenderConfigReader.RenderpassType.geometry || it.value.passConfig.type == RenderConfigReader.RenderpassType.lights) &&
                        it.value.passConfig.renderTransparent == node.material.blending.transparent
                }.forEach { pass ->
                        logger.debug("Initializing shader properties for ${node.name} in pass ${pass.key}")
                        val order = pass.value.getShaderPropertyOrder(node)

                        val shaderPropertyUbo = VulkanUBO(device, backingBuffer = buffers.ShaderProperties)
                        with(shaderPropertyUbo) {
                            name = "ShaderProperties"

                            order.forEach { name, offset ->
                                // TODO: See whether returning 0 on non-found shader property has ill side effects
                                add(name, { node.getShaderProperty(name) ?: 0 }, offset)
                            }

                            this.createUniformBuffer()
                            s.UBOs.put("${pass.key}-ShaderProperties", s.requiredDescriptorSets["ShaderProperties"]!! to this)
                        }
                    }

            }

            if(addInitializer) {
                lateResizeInitializers[node] = {
                    val reloaded = initializeCustomShadersForNode(node, addInitializer = false)

                    if(reloaded) {
                        node.rendererMetadata()?.texturesToDescriptorSets(device,
                            renderpasses.filter { pass -> pass.value.passConfig.type != RenderConfigReader.RenderpassType.quad },
                            descriptorPool)
                    }
                }
            }

//             TODO: Figure out if this can be avoided for the BDV integration
             s.clearTextureDescriptorSets()

            return true
        }

        return false
    }

    fun destroyNode(node: Node) {
        logger.trace("Destroying node ${node.name}...")
        if (!node.metadata.containsKey("VulkanRenderer")) {
            return
        }

        lateResizeInitializers.remove(node)
        node.initialized = false

        node.rendererMetadata()?.UBOs?.forEach { it.value.second.close() }

        if (node is HasGeometry) {
            node.rendererMetadata()?.vertexBuffers?.forEach {
                it.value.close()
            }
        }

        node.metadata.remove("VulkanRenderer")
    }

    /**
     * Returns true if the current VulkanTexture can be reused to store the information in the [GenericTexture]
     * [other]. Returns false otherwise.
     */
    protected fun VulkanTexture.canBeReused(other: GenericTexture, miplevels: Int, device: VulkanDevice): Boolean {
        return this.device == device &&
            this.width == other.dimensions.x().toInt() &&
            this.height == other.dimensions.y().toInt() &&
            this.depth == other.dimensions.z().toInt() &&
            this.mipLevels == miplevels

    }

    /**
     * Loads a texture given as string in [texture] from the classpath of this Node. Emits an error and falls back
     * to [fallback] in case the texture cannot be located.
     */
    protected fun Node.loadTextureFromJar(texture: String, generateMipmaps: Boolean, fallback: VulkanTexture): VulkanTexture {
        val f = texture.substringAfterLast("/")
        val stream = this@loadTextureFromJar.javaClass.getResourceAsStream(f)

        return if (stream == null) {
            logger.error("Not found: $f for ${this@loadTextureFromJar}")
            fallback
        } else {
            VulkanTexture.loadFromFile(device,
                commandPools, queue, queue, stream,
                texture.substringAfterLast("."), true, generateMipmaps)
        }
    }

    /**
     * Loads or reloads the textures for [node], updating it's internal renderer state stored in [s].
     */
    protected fun loadTexturesForNode(node: Node, s: VulkanObjectState): Boolean {
        val stats = hub?.get(SceneryElement.Statistics) as Statistics?
        val defaultTexture = textureCache["DefaultTexture"] ?: throw IllegalStateException("Default fallback texture does not exist.")
        // if a node is not yet initialized, we'll definitely require a new DS
        var reqNewDS = !node.initialized

        node.material.textures.forEach { type, texture ->
            val slot = VulkanObjectState.textureTypeToSlot(type)
            val generateMipmaps = GenericTexture.mipmappedObjectTextures.contains(type)

            logger.debug("${node.name} will have $type texture from $texture in slot $slot")

            if (!textureCache.containsKey(texture) || node.material.needsTextureReload) {
                try {
                    logger.trace("Loading texture $texture for ${node.name}")

                    val gt = node.material.transferTextures[texture.substringAfter("fromBuffer:")]

                    val vkTexture: VulkanTexture = if (texture.startsWith("fromBuffer:") && gt != null) {
                        val miplevels = if (generateMipmaps && gt.mipmap) {
                            Math.floor(Math.log(Math.min(gt.dimensions.x() * 1.0, gt.dimensions.y() * 1.0)) / Math.log(2.0)).toInt()
                        } else {
                            1
                        }

                        val existingTexture = s.textures[type]
                        val t: VulkanTexture = if (existingTexture != null && existingTexture.canBeReused(gt, miplevels, device)) {
                            existingTexture
                        } else {
                            reqNewDS = true
                            VulkanTexture(device, commandPools, queue, queue, gt, miplevels)
                        }

                        gt.contents?.let { contents ->
                            t.copyFrom(contents.duplicate())
                        }

                        if (gt.hasConsumableUpdates()) {
                            t.copyFrom(ByteBuffer.allocate(0))
                        }

                        if(reqNewDS) {
                            t.createSampler(gt)
                        }
                        t
                    } else {
                        val start = System.nanoTime()

                        val t = if (texture.contains("jar!")) {
                            node.loadTextureFromJar(texture, generateMipmaps, defaultTexture)
                        } else {
                            VulkanTexture.loadFromFile(device,
                                commandPools, queue, queue, texture, true, generateMipmaps)
                        }

                        val duration = System.nanoTime() - start * 1.0f
                        stats?.add("loadTexture", duration)
                        reqNewDS = true

                        t
                    }

                    // add new texture to texture list and cache, and close old texture
                    s.textures[type] = vkTexture
                    textureCache[texture] = vkTexture
                } catch (e: Exception) {
                    logger.warn("Could not load texture for ${node.name}: $e")
                }
            } else {
                s.textures[type] = textureCache[texture]!!
            }
        }

        GenericTexture.objectTextures.forEach {
            if (!s.textures.containsKey(it)) {
                s.textures.putIfAbsent(it, defaultTexture)
                s.defaultTexturesFor.add(it)
            }
        }

        if(reqNewDS) {
            s.texturesToDescriptorSets(device,
                renderpasses.filter { it.value.passConfig.type != RenderConfigReader.RenderpassType.quad },
                descriptorPool)
        }

        return reqNewDS
    }

    protected fun prepareDefaultDescriptorSetLayouts(device: VulkanDevice): ConcurrentHashMap<String, Long> {
        val m = ConcurrentHashMap<String, Long>()

        m["Matrices"] = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1)),
            0,
            VK_SHADER_STAGE_ALL)

        m["MaterialProperties"] = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1)),
            0,
            VK_SHADER_STAGE_ALL)

        m["LightParameters"] = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1)),
            0,
            VK_SHADER_STAGE_ALL)

        m["VRParameters"] = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1)),
            0,
            VK_SHADER_STAGE_ALL)

        return m
    }

    protected fun prepareDescriptorSets(device: VulkanDevice, descriptorPool: Long) {
        this.descriptorSets["Matrices"] = VU.createDescriptorSetDynamic(device, descriptorPool,
                descriptorSetLayouts["Matrices"]!!, 1,
                buffers.UBOs)

        this.descriptorSets["MaterialProperties"] = VU.createDescriptorSetDynamic(device, descriptorPool,
                descriptorSetLayouts["MaterialProperties"]!!, 1,
                buffers.UBOs)

        val lightUbo = VulkanUBO(device)
        lightUbo.add("ViewMatrix0", { GLMatrix.getIdentity() })
        lightUbo.add("ViewMatrix1", { GLMatrix.getIdentity() })
        lightUbo.add("InverseViewMatrix0", { GLMatrix.getIdentity() })
        lightUbo.add("InverseViewMatrix1", { GLMatrix.getIdentity() })
        lightUbo.add("ProjectionMatrix", { GLMatrix.getIdentity() })
        lightUbo.add("InverseProjectionMatrix", { GLMatrix.getIdentity() })
        lightUbo.add("CamPosition", { GLVector.getNullVector(3) })
        lightUbo.createUniformBuffer()
        lightUbo.populate()

        defaultUBOs["LightParameters"] = lightUbo

        this.descriptorSets["LightParameters"] = VU.createDescriptorSet(device, descriptorPool,
                descriptorSetLayouts["LightParameters"]!!, 1,
                lightUbo.descriptor)

        val vrUbo = VulkanUBO(device)

        vrUbo.add("projection0", { GLMatrix.getIdentity() } )
        vrUbo.add("projection1", { GLMatrix.getIdentity() } )
        vrUbo.add("inverseProjection0", { GLMatrix.getIdentity() } )
        vrUbo.add("inverseProjection1", { GLMatrix.getIdentity() } )
        vrUbo.add("headShift", { GLMatrix.getIdentity() })
        vrUbo.add("IPD", { 0.0f })
        vrUbo.add("stereoEnabled", { 0 })
        vrUbo.createUniformBuffer()
        vrUbo.populate()

        defaultUBOs["VRParameters"] = vrUbo

        this.descriptorSets["VRParameters"] = VU.createDescriptorSet(device, descriptorPool,
                descriptorSetLayouts["VRParameters"]!!, 1,
                vrUbo.descriptor)
    }

    protected fun prepareStandardVertexDescriptors(): ConcurrentHashMap<VertexDataKinds, VertexDescription> {
        val map = ConcurrentHashMap<VertexDataKinds, VertexDescription>()

        VertexDataKinds.values().forEach { kind ->
            val attributeDesc: VkVertexInputAttributeDescription.Buffer?
            var stride = 0

            when (kind) {
                VertexDataKinds.None -> {
                    stride = 0
                    attributeDesc = null
                }

                VertexDataKinds.PositionNormal -> {
                    stride = 3 + 3
                    attributeDesc = VkVertexInputAttributeDescription.calloc(2)

                    attributeDesc.get(1)
                        .binding(0)
                        .location(1)
                        .format(VK_FORMAT_R32G32B32_SFLOAT)
                        .offset(3 * 4)
                }

                VertexDataKinds.PositionNormalTexcoord -> {
                    stride = 3 + 3 + 2
                    attributeDesc = VkVertexInputAttributeDescription.calloc(3)

                    attributeDesc.get(1)
                        .binding(0)
                        .location(1)
                        .format(VK_FORMAT_R32G32B32_SFLOAT)
                        .offset(3 * 4)

                    attributeDesc.get(2)
                        .binding(0)
                        .location(2)
                        .format(VK_FORMAT_R32G32_SFLOAT)
                        .offset(3 * 4 + 3 * 4)
                }

                VertexDataKinds.PositionTexcoords -> {
                    stride = 3 + 2
                    attributeDesc = VkVertexInputAttributeDescription.calloc(2)

                    attributeDesc.get(1)
                        .binding(0)
                        .location(1)
                        .format(VK_FORMAT_R32G32_SFLOAT)
                        .offset(3 * 4)
                }
            }

            attributeDesc?.let {
                if(it.capacity() > 0) {
                    it.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
                }
            }

            val bindingDesc: VkVertexInputBindingDescription.Buffer? = if (attributeDesc != null) {
                VkVertexInputBindingDescription.calloc(1)
                    .binding(0)
                    .stride(stride * 4)
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            } else {
                null
            }

            val inputState = VkPipelineVertexInputStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pNext(NULL)
                .pVertexAttributeDescriptions(attributeDesc)
                .pVertexBindingDescriptions(bindingDesc)

            map[kind] = VertexDescription(inputState, attributeDesc, bindingDesc)
        }

        return map
    }

    /** Data class for encapsulation of shader vertex attributes */
    data class AttributeInfo(val format: Int, val elementByteSize: Int, val elementCount: Int)

    /**
     * Calculates the formats and required sizes for the elements contained in this hash map when
     * used as definition for vertex attributes in a shader.
     */
    protected fun HashMap<String, () -> Any>.getFormatsAndRequiredAttributeSize(): List<AttributeInfo> {
        return this.map {
            val value = it.value.invoke()

            when (value.javaClass) {
                GLVector::class.java -> {
                    val v = value as GLVector
                    when {
                        v.toFloatArray().size == 2 -> graphics.scenery.backends.vulkan.VulkanRenderer.AttributeInfo(VK_FORMAT_R32G32_SFLOAT, 4 * 2, 1)
                        v.toFloatArray().size == 4 -> graphics.scenery.backends.vulkan.VulkanRenderer.AttributeInfo(VK_FORMAT_R32G32B32A32_SFLOAT, 4 * 4, 1)
                        else -> {
                            logger.error("Unsupported vector length for instancing: ${v.toFloatArray().size}")
                            graphics.scenery.backends.vulkan.VulkanRenderer.AttributeInfo(-1, -1, -1)
                        }
                    }
                }

                GLMatrix::class.java -> {
                    val m = value as GLMatrix
                    graphics.scenery.backends.vulkan.VulkanRenderer.AttributeInfo(VK_FORMAT_R32G32B32A32_SFLOAT, 4 * 4, m.floatArray.size / 4)
                }

                else -> {
                    logger.error("Unsupported type for instancing: ${value.javaClass.simpleName}")
                    graphics.scenery.backends.vulkan.VulkanRenderer.AttributeInfo(-1, -1, -1)
                }
            }
        }
    }

    protected fun vertexDescriptionFromInstancedNode(node: Node, template: VertexDescription): VertexDescription {
        logger.debug("Creating instanced vertex description for ${node.name}")

        if(template.attributeDescription == null || template.bindingDescription == null) {
            return template
        }

        val attributeDescs = template.attributeDescription
        val bindingDescs = template.bindingDescription

        val formatsAndAttributeSizes = node.instancedProperties.getFormatsAndRequiredAttributeSize()
        val newAttributesNeeded = formatsAndAttributeSizes.map { it.elementCount }.sum()

        val newAttributeDesc = VkVertexInputAttributeDescription
            .calloc(attributeDescs.capacity() + newAttributesNeeded)

        var position: Int
        var offset = 0

        for(i in 0 until attributeDescs.capacity()) {
            newAttributeDesc[i].set(attributeDescs[i])
            offset += newAttributeDesc[i].offset()
            logger.debug("location(${newAttributeDesc[i].location()})")
            logger.debug("    .offset(${newAttributeDesc[i].offset()})")
            position = i
        }

        position = 3
        offset = 0

        formatsAndAttributeSizes.zip(node.instancedProperties.toList().reversed()).forEach {
            val attribInfo = it.first
            val property = it.second

            for(i in (0 until attribInfo.elementCount)) {
                newAttributeDesc[position]
                    .binding(1)
                    .location(position)
                    .format(attribInfo.format)
                    .offset(offset)

                logger.debug("location($position, $i/${attribInfo.elementCount}) for ${property.first}, type: ${property.second.invoke().javaClass.simpleName}")
                logger.debug("   .format(${attribInfo.format})")
                logger.debug("   .offset($offset)")

                offset += attribInfo.elementByteSize
                position++
            }
        }

        logger.debug("stride($offset), ${bindingDescs.capacity()}")

        val newBindingDesc = VkVertexInputBindingDescription.calloc(bindingDescs.capacity() + 1)
        newBindingDesc[0].set(bindingDescs[0])
        newBindingDesc[1]
            .binding(1)
            .stride(offset)
            .inputRate(VK_VERTEX_INPUT_RATE_INSTANCE)

        val inputState = VkPipelineVertexInputStateCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            .pNext(NULL)
            .pVertexAttributeDescriptions(newAttributeDesc)
            .pVertexBindingDescriptions(newBindingDesc)

        return VertexDescription(inputState, newAttributeDesc, newBindingDesc)
    }

    protected fun prepareDefaultTextures(device: VulkanDevice) {
        val t = VulkanTexture.loadFromFile(device, commandPools, queue, queue,
            Renderer::class.java.getResourceAsStream("DefaultTexture.png"), "png", true, true)

        textureCache["DefaultTexture"] = t
    }

    protected fun prepareRenderpassesFromConfig(config: RenderConfigReader.RenderConfig, windowWidth: Int, windowHeight: Int) {
        // create all renderpasses first
        val framebuffers = ConcurrentHashMap<String, VulkanFramebuffer>()

        flow = renderConfig.createRenderpassFlow()
        logger.debug("Renderpasses to be run: ${flow.joinToString(", ")}")

        descriptorSetLayouts
            .filter { it.key.startsWith("outputs-") }
            .map {
                logger.debug("Marking RT DSL ${it.value.toHexString()} for deletion")
                vkDestroyDescriptorSetLayout(device.vulkanDevice, it.value, null)
                it.key
            }
            .map {
                descriptorSetLayouts.remove(it)
            }

        renderConfig.renderpasses.filter { it.value.inputs != null }
            .flatMap { rp ->
                rp.value.inputs!!
            }
            .map {
                renderConfig.rendertargets.let { rts ->
                    val name = if (it.contains(".")) {
                        it.substringBefore(".")
                    } else {
                        it
                    }

                    name to rts[name]!!
                }
            }
            .map { rt ->
                if(!descriptorSetLayouts.containsKey("outputs-${rt.first}")) {
                    logger.debug("Creating output descriptor set for ${rt.first}")
                    // create descriptor set layout that matches the render target
                    descriptorSetLayouts["outputs-${rt.first}"] = VU.createDescriptorSetLayout(device,
                        descriptorNum = rt.second.attachments.count(),
                        descriptorCount = 1,
                        type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    )
                }
            }

        config.createRenderpassFlow().map { passName ->
            val passConfig = config.renderpasses.getValue(passName)
            val pass = VulkanRenderpass(passName, config, device, descriptorPool, pipelineCache, vertexDescriptors)

            var width = windowWidth
            var height = windowHeight

            // create framebuffer
            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                config.rendertargets.filter { it.key == passConfig.output }.map { rt ->
                    width = (settings.get<Float>("Renderer.SupersamplingFactor") * windowWidth * rt.value.size.first).toInt()
                    height = (settings.get<Float>("Renderer.SupersamplingFactor") * windowHeight * rt.value.size.second).toInt()

                    logger.info("Creating render framebuffer ${rt.key} for pass $passName (${width}x${height})")

                    settings.set("Renderer.$passName.displayWidth", width)
                    settings.set("Renderer.$passName.displayHeight", height)

                    if (framebuffers.containsKey(rt.key)) {
                        logger.info("Reusing already created framebuffer")
                        pass.output.put(rt.key, framebuffers.getValue(rt.key))
                    } else {

                        // create framebuffer -- don't clear it, if blitting is needed
                        val framebuffer = VulkanFramebuffer(this@VulkanRenderer.device, commandPools.Standard,
                            width, height, this,
                            shouldClear = !passConfig.blitInputs,
                            sRGB = renderConfig.sRGB)

                        rt.value.attachments.forEach { att ->
                            logger.info(" + attachment ${att.key}, ${att.value.name}")

                            when (att.value) {
                                RenderConfigReader.TargetFormat.RGBA_Float32 -> framebuffer.addFloatRGBABuffer(att.key, 32)
                                RenderConfigReader.TargetFormat.RGBA_Float16 -> framebuffer.addFloatRGBABuffer(att.key, 16)

                                RenderConfigReader.TargetFormat.RGB_Float32 -> framebuffer.addFloatRGBBuffer(att.key, 32)
                                RenderConfigReader.TargetFormat.RGB_Float16 -> framebuffer.addFloatRGBBuffer(att.key, 16)

                                RenderConfigReader.TargetFormat.RG_Float32 -> framebuffer.addFloatRGBuffer(att.key, 32)
                                RenderConfigReader.TargetFormat.RG_Float16 -> framebuffer.addFloatRGBuffer(att.key, 16)

                                RenderConfigReader.TargetFormat.RGBA_UInt16 -> framebuffer.addUnsignedByteRGBABuffer(att.key, 16)
                                RenderConfigReader.TargetFormat.RGBA_UInt8 -> framebuffer.addUnsignedByteRGBABuffer(att.key, 8)
                                RenderConfigReader.TargetFormat.R_UInt16 -> framebuffer.addUnsignedByteRBuffer(att.key, 16)
                                RenderConfigReader.TargetFormat.R_UInt8 -> framebuffer.addUnsignedByteRBuffer(att.key, 8)

                                RenderConfigReader.TargetFormat.Depth32 -> framebuffer.addDepthBuffer(att.key, 32)
                                RenderConfigReader.TargetFormat.Depth24 -> framebuffer.addDepthBuffer(att.key, 24)
                                RenderConfigReader.TargetFormat.R_Float16 -> framebuffer.addFloatBuffer(att.key, 16)
                            }

                        }

                        framebuffer.createRenderpassAndFramebuffer()
                        framebuffer.outputDescriptorSet = VU.createRenderTargetDescriptorSet(this@VulkanRenderer.device,
                            descriptorPool, descriptorSetLayouts["outputs-${rt.key}"]!!, rt.value.attachments, framebuffer)

                        pass.output[rt.key] = framebuffer
                        framebuffers.put(rt.key, framebuffer)
                    }
                }

                pass.commandBufferCount = swapchain.images.size

                if (passConfig.output == "Viewport") {
                    // create viewport renderpass with swapchain image-derived framebuffer
                    pass.isViewportRenderpass = true
                    width = if(renderConfig.stereoEnabled) {
                        windowWidth// * 2
                    } else {
                        windowWidth
                    }

                    height = windowHeight

                    swapchain.images.forEachIndexed { i, _ ->
                        val fb = VulkanFramebuffer(this@VulkanRenderer.device, commandPools.Standard,
                            width, height, this@with, sRGB = renderConfig.sRGB)

                        fb.addSwapchainAttachment("swapchain-$i", swapchain, i)
                        fb.addDepthBuffer("swapchain-$i-depth", 32)
                        fb.createRenderpassAndFramebuffer()

                        pass.output["Viewport-$i"] = fb
                    }
                }

                pass.vulkanMetadata.clearValues?.free()
                if(!passConfig.blitInputs) {
                    pass.vulkanMetadata.clearValues = VkClearValue.calloc(pass.output.values.first().attachments.count())
                    pass.vulkanMetadata.clearValues?.let { clearValues ->

                        pass.output.values.first().attachments.values.forEachIndexed { i, att ->
                            when (att.type) {
                                VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> {
                                    clearValues[i].color().float32().put(pass.passConfig.clearColor.toFloatArray())
                                }
                                VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> {
                                    clearValues[i].depthStencil().set(pass.passConfig.depthClearValue, 0)
                                }
                            }
                        }
                    }
                } else {
                    pass.vulkanMetadata.clearValues = null
                }

                pass.vulkanMetadata.renderArea.extent().set(
                    (pass.passConfig.viewportSize.first * width).toInt(),
                    (pass.passConfig.viewportSize.second * height).toInt())
                pass.vulkanMetadata.renderArea.offset().set(
                    (pass.passConfig.viewportOffset.first * width).toInt(),
                    (pass.passConfig.viewportOffset.second * height).toInt())
                logger.debug("Render area for $passName: ${pass.vulkanMetadata.renderArea.extent().width()}x${pass.vulkanMetadata.renderArea.extent().height()}")

                pass.vulkanMetadata.viewport[0].set(
                    (pass.passConfig.viewportOffset.first * width),
                    (pass.passConfig.viewportOffset.second * height),
                    (pass.passConfig.viewportSize.first * width),
                    (pass.passConfig.viewportSize.second * height),
                    0.0f, 1.0f)

                pass.vulkanMetadata.scissor[0].extent().set(
                    (pass.passConfig.viewportSize.first * width).toInt(),
                    (pass.passConfig.viewportSize.second * height).toInt())

                pass.vulkanMetadata.scissor[0].offset().set(
                    (pass.passConfig.viewportOffset.first * width).toInt(),
                    (pass.passConfig.viewportOffset.second * height).toInt())

                pass.vulkanMetadata.eye.put(0, pass.passConfig.eye)

                endCommandBuffer(this@VulkanRenderer.device, commandPools.Standard, this@VulkanRenderer.queue, flush = true)
            }

            renderpasses.put(passName, pass)
        }

        // connect inputs with each othe
        renderpasses.forEach { pass ->
            val passConfig = config.renderpasses.getValue(pass.key)

            passConfig.inputs?.forEach { inputTarget ->
                val targetName = if(inputTarget.contains(".")) {
                    inputTarget.substringBefore(".")
                } else {
                    inputTarget
                }
                renderpasses.filter {
                    it.value.output.keys.contains(targetName)
                }.forEach { pass.value.inputs[inputTarget] = it.value.output.getValue(targetName) }
            }

            with(pass.value) {
                initializeShaderParameterDescriptorSetLayouts(settings)

                initializeDefaultPipeline()
            }
        }
    }

    protected fun prepareStandardSemaphores(device: VulkanDevice): ConcurrentHashMap<StandardSemaphores, Array<Long>> {
        val map = ConcurrentHashMap<StandardSemaphores, Array<Long>>()

        StandardSemaphores.values().forEach {
            map[it] = swapchain.images.map { i ->
                VU.getLong("Semaphore for $i",
                    { vkCreateSemaphore(device.vulkanDevice, semaphoreCreateInfo, null, this) }, {})
            }.toTypedArray()
        }

        return map
    }

    /**
     * Polls for window events and triggers swapchain recreation if necessary.
     * Returns true if the swapchain has been recreated, or false if not.
     */
    private fun pollEvents(): Boolean {
        window.pollEvents()

        (swapchain as? HeadlessSwapchain)?.queryResize()

        if (swapchainRecreator.mustRecreate) {
            swapchainRecreator.recreate()
            frames = 0

            return true
        }

        return false
    }

    private fun beginFrame() {
        swapchainRecreator.mustRecreate = swapchain.next(timeout = UINT64_MAX,
            signalSemaphore = semaphores.getValue(StandardSemaphores.PresentComplete)[0])
    }

    @Suppress("unused")
    override fun recordMovie(filename: String, overwrite: Boolean) {
        if(recordMovie) {
            encoder?.finish()
            encoder = null

            recordMovie = false
        } else {
            movieFilename = filename
            recordMovie = true
        }
    }

    private fun submitFrame(queue: VkQueue, pass: VulkanRenderpass, commandBuffer: VulkanCommandBuffer, present: PresentHelpers) {
        if(swapchainRecreator.mustRecreate) {
            return
        }

        val stats = hub?.get(SceneryElement.Statistics) as? Statistics
        present.submitInfo
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pNext(NULL)
            .waitSemaphoreCount(1)
            .pWaitSemaphores(present.waitSemaphore)
            .pWaitDstStageMask(present.waitStages)
            .pCommandBuffers(present.commandBuffers)
            .pSignalSemaphores(present.signalSemaphore)

        // Submit to the graphics queue
        VU.run("Submit viewport render queue", { vkQueueSubmit(queue, present.submitInfo, commandBuffer.getFence()) })

        val startPresent = System.nanoTime()
        commandBuffer.submitted = true
        swapchain.present(ph.signalSemaphore)

        swapchain.postPresent(pass.getReadPosition())

        // submit to OpenVR if attached
        if(hub?.getWorkingHMDDisplay()?.hasCompositor() == true) {
            hub?.getWorkingHMDDisplay()?.wantsVR()?.submitToCompositorVulkan(
                window.width, window.height,
                swapchain.format,
                instance, device, queue,
                swapchain.images[pass.getReadPosition()])
        }

        if (recordMovie || screenshotRequested || imageRequests.isNotEmpty()) {
            val request = try {
                imageRequests.poll()
            } catch(e: NoSuchElementException) {
                null
            }

            // default image format is 32bit BGRA
            val imageByteSize = window.width * window.height * 4L
            if(screenshotBuffer == null || screenshotBuffer?.size != imageByteSize) {
                logger.debug("Reallocating screenshot buffer")
                screenshotBuffer = VulkanBuffer(device, imageByteSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    wantAligned = true)
            }

            if(imageBuffer == null || imageBuffer?.capacity() != imageByteSize.toInt()) {
                logger.debug("Reallocating image buffer")
                imageBuffer = memAlloc(imageByteSize.toInt())
            }

            // finish encoding if a resize was performed
            if(recordMovie) {
                if (encoder != null && (encoder?.frameWidth != window.width || encoder?.frameHeight != window.height)) {
                    encoder?.finish()
                }

                if (encoder == null || encoder?.frameWidth != window.width || encoder?.frameHeight != window.height) {
                    val file = SystemHelpers.addFileCounter(if(movieFilename == "") {
                        File(System.getProperty("user.home"), "Desktop" + File.separator + "$applicationName - ${SystemHelpers.formatDateTime()}.mp4")
                    } else {
                        File(movieFilename)
                    }, false)

                    encoder = H264Encoder(
                        (window.width * settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
                        (window.height* settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
                        file.absolutePath,
                        hub = hub)
                }
            }

            screenshotBuffer?.let { sb ->
                with(VU.newCommandBuffer(device, commandPools.Render, autostart = true)) {
                    val subresource = VkImageSubresourceLayers.calloc()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1)

                    val regions = VkBufferImageCopy.calloc(1)
                        .bufferRowLength(0)
                        .bufferImageHeight(0)
                        .imageOffset(VkOffset3D.calloc().set(0, 0, 0))
                        .imageExtent(VkExtent3D.calloc().set(window.width, window.height, 1))
                        .imageSubresource(subresource)

                    val image = swapchain.images[pass.getReadPosition()]

                    VulkanTexture.transitionLayout(image,
                        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        commandBuffer = this)

                    vkCmdCopyImageToBuffer(this, image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        sb.vulkanBuffer,
                        regions)

                    VulkanTexture.transitionLayout(image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        commandBuffer = this)

                    endCommandBuffer(this@VulkanRenderer.device, commandPools.Render, queue,
                        flush = true, dealloc = true)
                }

                if(screenshotRequested || request != null) {
                    sb.copyTo(imageBuffer!!)
                }

                if(recordMovie) {
                    encoder?.encodeFrame(sb.mapIfUnmapped().getByteBuffer(imageByteSize.toInt()))
                }

                if((screenshotRequested || request != null) && !recordMovie) {
                    sb.close()
                    screenshotBuffer = null
                }
            }

            if(screenshotRequested || request != null) {
                val writeToFile = screenshotRequested
                // reorder bytes for screenshot in a separate thread
                thread {
                    imageBuffer?.let { ib ->
                        try {
                            val file = SystemHelpers.addFileCounter(if(screenshotFilename == "") {
                                File(System.getProperty("user.home"), "Desktop" + File.separator + "$applicationName - ${SystemHelpers.formatDateTime()}.png")
                            } else {
                                File(screenshotFilename)
                            }, screenshotOverwriteExisting)
                            file.createNewFile()
                            ib.rewind()

                            val imageArray = ByteArray(ib.remaining())
                            ib.get(imageArray)
                            val shifted = ByteArray(imageArray.size)
                            ib.flip()

                            // swizzle BGRA -> ABGR
                            for (i in 0 until shifted.size step 4) {
                                shifted[i] = imageArray[i + 3]
                                shifted[i + 1] = imageArray[i]
                                shifted[i + 2] = imageArray[i + 1]
                                shifted[i + 3] = imageArray[i + 2]
                            }

                            val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_4BYTE_ABGR)
                            val imgData = (image.raster.dataBuffer as DataBufferByte).data
                            System.arraycopy(shifted, 0, imgData, 0, shifted.size)

                            if(request != null && request is RenderedImage.RenderedRGBAImage) {
                                request.width = window.width
                                request.height = window.height
                                request.data = imgData
                            }

                            if(writeToFile) {
                                ImageIO.write(image, "png", file)
                                logger.info("Screenshot saved to ${file.absolutePath}")
                            }
                        } catch (e: Exception) {
                            logger.error("Unable to take screenshot: ")
                            e.printStackTrace()
                        } finally {
//                            memFree(ib)
                        }
                    }
                }

                screenshotOverwriteExisting = false
                screenshotRequested = false
            }
        }

        val presentDuration = System.nanoTime() - startPresent
        stats?.add("Renderer.viewportSubmitAndPresent", presentDuration)

        firstImageReady = true
    }

    /**
     * This function renders the scene
     */
    override fun render() = runBlocking {
        val profiler = hub?.get<Profiler>()
//        profiler?.begin("Renderer.Housekeeping")
        val swapchainChanged = pollEvents()

        if(shouldClose) {
            closeInternal()
            return@runBlocking
        }

        val stats = hub?.get(SceneryElement.Statistics) as? Statistics
        val sceneObjects = GlobalScope.async {
            scene.discover(scene, { n ->
                n is HasGeometry
                    && n.visible
            }, useDiscoveryBarriers = true)
        }

        // check whether scene is already initialized
        if (scene.children.count() == 0 || !scene.initialized) {
            initializeScene()

            delay(200)
            return@runBlocking
        }

        if (toggleFullscreen) {
            vkDeviceWaitIdle(device.vulkanDevice)

            switchFullscreen()
            toggleFullscreen = false
            return@runBlocking
        }

        if (window.shouldClose) {
            shouldClose = true
            // stop all
            vkDeviceWaitIdle(device.vulkanDevice)
            return@runBlocking
        }

        if (renderDelay > 0) {
            logger.warn("Delaying next frame for $renderDelay ms, as one or more validation error have occured in the previous frame.")
            delay(renderDelay)
        }
//        profiler?.end()

        profiler?.begin("Renderer.updateUBOs")
        val startUboUpdate = System.nanoTime()
        val ubosUpdated = updateDefaultUBOs(device)
        stats?.add("Renderer.updateUBOs", System.nanoTime() - startUboUpdate)
        profiler?.end()

        profiler?.begin("Renderer.updateInstanceBuffers")
        val startInstanceUpdate = System.nanoTime()
        val instancesUpdated = updateInstanceBuffers(sceneObjects)
        stats?.add("Renderer.updateInstanceBuffers", System.nanoTime() - startInstanceUpdate)
        profiler?.end()

        // flag set to true if command buffer re-recording is necessary,
        // e.g. because of scene or pipeline changes
        var forceRerecording = instancesUpdated
        val rerecordingCauses = ArrayList<String>(20)

        profiler?.begin("Renderer.PreDraw")
        // here we discover the objects in the scene that could be relevant for the scene
        if (renderpasses.filter { it.value.passConfig.type != RenderConfigReader.RenderpassType.quad }.any()) {
            sceneObjects.await().forEach {
                // if a node is not initialized yet, it'll be initialized here and it's UBO updated
                // in the next round
                if (it.rendererMetadata() == null) {
                    logger.debug("${it.name} is not initialized, doing that now")
                    it.metadata["VulkanRenderer"] = VulkanObjectState()
                    initializeNode(it)

                    return@forEach
                }

                it.preDraw()

                // the current command buffer will be forced to be re-recorded if either geometry, blending or
                // texturing of a given node have changed, as these might change pipelines or descriptor sets, leading
                // to the original command buffer becoming obsolete.
                it.rendererMetadata()?.let { metadata ->
                    if (it.dirty) {
                        logger.debug("Force command buffer re-recording, as geometry for {} has been updated", it.name)

                        it.preUpdate(this@VulkanRenderer, hub)
                        updateNodeGeometry(it)
                        it.dirty = false

                        rerecordingCauses.add(it.name)
                        forceRerecording = true
                    }

                    // this covers cases where a master node is not given any instanced properties in the beginning
                    // but only later, or when instancing is removed at some point.
                    if((!metadata.instanced && (it.instancedProperties.size > 0 && it.instances.size > 0)) ||
                        metadata.instanced && it.instancedProperties.size == 0 && it.instances.size == 0) {
                        metadata.initialized = false
                        initializeNode(it)
                        return@forEach
                    }

                    val material = it.material
                    if (material.needsTextureReload) {
                        logger.trace("Force command buffer re-recording, as reloading textures for ${it.name}")
                        val reload = loadTexturesForNode(it, metadata)

                        it.material.needsTextureReload = false

                        if(reload) {
                            rerecordingCauses.add(it.name)
                            forceRerecording = true
                        }
                    }

                    if (material.materialHashCode() != metadata.materialHashCode || (material is ShaderMaterial && material.shaders.stale)) {
                        logger.trace("Force command buffer re-recording, as blending options for ${it.name} have changed")
                        val reloaded = initializeCustomShadersForNode(it)
                        logger.debug("Material is stale, re-recording, reloaded=$reloaded")
                        metadata.materialHashCode = it.material.materialHashCode()

                        // if we reloaded the node's shaders, we might need to recreate its texture descriptor sets
                        if(reloaded) {
                            it.rendererMetadata()?.texturesToDescriptorSets(device,
                                renderpasses.filter { pass -> pass.value.passConfig.type != RenderConfigReader.RenderpassType.quad },
                                descriptorPool)
                        }

                        rerecordingCauses.add(it.name)
                        forceRerecording = true

                        (material as? ShaderMaterial)?.shaders?.stale = false
                    }
                }
            }

            if(pushMode) {
                val newSceneArray = sceneObjects.getCompleted().toHashSet()
                if (!newSceneArray.equals(sceneArray)) {
                    forceRerecording = true
                }

                sceneArray = newSceneArray
            }
        }
        profiler?.end()

        profiler?.begin("Renderer.BeginFrame")
        val presentedFrames = swapchain.presentedFrames()
        // return if neither UBOs were updated, nor the scene was modified
        if (pushMode && !swapchainChanged && !ubosUpdated && !forceRerecording && !screenshotRequested && totalFrames > 3 && presentedFrames > 3) {
            logger.trace("UBOs have not been updated, returning (pushMode={}, swapchainChanged={}, ubosUpdated={}, forceRerecording={}, screenshotRequested={})", pushMode, swapchainChanged, ubosUpdated, forceRerecording, totalFrames)
            delay(2)

            return@runBlocking
        }

        beginFrame()

        // firstWaitSemaphore is now the RenderComplete semaphore of the previous pass
        firstWaitSemaphore.put(0, semaphores.getValue(StandardSemaphores.PresentComplete)[0])

        val si = VkSubmitInfo.calloc()

        var waitSemaphore = semaphores.getValue(StandardSemaphores.PresentComplete)[0]

        profiler?.end()

        flow.take(flow.size - 1).forEachIndexed { i, t ->
            profiler?.begin("Renderer.$t")
            logger.trace("Running pass {}", t)
            val target = renderpasses[t]!!
            val commandBuffer = target.commandBuffer

            if (commandBuffer.submitted) {
                commandBuffer.waitForFence()
                commandBuffer.submitted = false
                commandBuffer.resetFence()

                val timing = intArrayOf(0,0)
                VU.run("getting query pool results", { vkGetQueryPoolResults(device.vulkanDevice, timestampQueryPool, 2*i, 2, timing, 0, VK_FLAGS_NONE)})

                stats?.add("Renderer.$t.gpuTiming", timing[1] - timing[0])
            }

            val start = System.nanoTime()

            when (target.passConfig.type) {
                RenderConfigReader.RenderpassType.geometry -> recordSceneRenderCommands(target, commandBuffer, sceneObjects, { it !is Light }, forceRerecording)
                RenderConfigReader.RenderpassType.lights -> recordSceneRenderCommands(target, commandBuffer, sceneObjects, { it is Light }, forceRerecording)
                RenderConfigReader.RenderpassType.quad -> recordPostprocessRenderCommands(target, commandBuffer)
            }

            stats?.add("VulkanRenderer.$t.recordCmdBuffer", System.nanoTime() - start)

            target.updateShaderParameters()

            target.submitCommandBuffers.put(0, commandBuffer.commandBuffer!!)
            target.signalSemaphores.put(0, target.semaphore)
            target.waitSemaphores.put(0, waitSemaphore)
            target.waitStages.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)

            si.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pNext(NULL)
                .waitSemaphoreCount(1)
                .pWaitDstStageMask(target.waitStages)
                .pCommandBuffers(target.submitCommandBuffers)
                .pSignalSemaphores(target.signalSemaphores)
                .pWaitSemaphores(target.waitSemaphores)

            if(swapchainRecreator.mustRecreate) {
                return@runBlocking
            }

            VU.run("Submit pass $t render queue", { vkQueueSubmit(queue, si, commandBuffer.getFence() )})

            commandBuffer.submitted = true
            firstWaitSemaphore.put(0, target.semaphore)
            waitSemaphore = target.semaphore

            profiler?.end()
        }

        si.free()

        profiler?.begin("Renderer.${renderpasses.keys.last()}")
        val viewportPass = renderpasses.values.last()
        val viewportCommandBuffer = viewportPass.commandBuffer
        if(viewportCommandBuffer.submitted) {
            viewportCommandBuffer.waitForFence()
        }
        logger.trace("Running viewport pass {}", renderpasses.keys.last())

        val start = System.nanoTime()

        when (viewportPass.passConfig.type) {
            RenderConfigReader.RenderpassType.geometry -> recordSceneRenderCommands(viewportPass, viewportCommandBuffer, sceneObjects, { it !is Light }, forceRerecording)
            RenderConfigReader.RenderpassType.lights -> recordSceneRenderCommands(viewportPass, viewportCommandBuffer, sceneObjects, { it is Light })
            RenderConfigReader.RenderpassType.quad -> recordPostprocessRenderCommands(viewportPass, viewportCommandBuffer)
        }

        stats?.add("VulkanRenderer.${viewportPass.name}.recordCmdBuffer", System.nanoTime() - start)

        if(viewportCommandBuffer.submitted) {
            viewportCommandBuffer.waitForFence()
            viewportCommandBuffer.submitted = false
            viewportCommandBuffer.resetFence()

            val timing = intArrayOf(0,0)
            VU.run("getting query pool results", { vkGetQueryPoolResults(device.vulkanDevice, timestampQueryPool, 2*(flow.size-1), 2, timing, 0, VK_FLAGS_NONE)})

            stats?.add("Renderer.${viewportPass.name}.gpuTiming", timing[1] - timing[0])
        }

        viewportPass.updateShaderParameters()

        ph.commandBuffers.put(0, viewportCommandBuffer.commandBuffer!!)
        ph.waitStages.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
        ph.signalSemaphore.put(0, semaphores.getValue(StandardSemaphores.RenderComplete)[0])
        ph.waitSemaphore.put(0, firstWaitSemaphore.get(0))
        profiler?.end()

        profiler?.begin("Renderer.SubmitFrame")
        submitFrame(queue, viewportPass, viewportCommandBuffer, ph)

        updateTimings()
        profiler?.end()
    }

    private fun updateTimings() {
        val thisTime = System.nanoTime()
        val duration = thisTime - lastTime
        time += duration / 1E9f
        lastTime = thisTime

//        scene.activeObserver?.deltaT = duration / 10E6f

        frames++
        totalFrames++
    }

    private fun createInstance(requiredExtensions: PointerBuffer? = null): VkInstance {
        return stackPush().use { stack ->
            val appInfo = VkApplicationInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8(applicationName))
                .pEngineName(stack.UTF8("scenery"))
                .apiVersion(VK_MAKE_VERSION(1, 0, 73))

            val additionalExts: List<String> = hub?.getWorkingHMDDisplay()?.getVulkanInstanceExtensions() ?: listOf()
            val utf8Exts = additionalExts.map { stack.UTF8(it) }

            logger.debug("HMD required instance exts: ${additionalExts.joinToString(", ")} ${additionalExts.size}")

            // allocate enough pointers for already pre-required extensions, plus HMD-required extensions, plus the debug extension
            val size = requiredExtensions?.remaining() ?: 0
            val enabledExtensionNames = stack.callocPointer(size + additionalExts.size + 2)

            if(requiredExtensions != null) {
                enabledExtensionNames.put(requiredExtensions)
            }

            val platformSurfaceExtension = when {
                Platform.get() === Platform.WINDOWS -> stack.UTF8(VK_KHR_WIN32_SURFACE_EXTENSION_NAME)
                Platform.get() === Platform.LINUX -> stack.UTF8(VK_KHR_XLIB_SURFACE_EXTENSION_NAME)
                Platform.get() === Platform.MACOSX -> stack.UTF8(VK_MVK_MACOS_SURFACE_EXTENSION_NAME)
                else -> throw UnsupportedOperationException("Vulkan is not supported on ${Platform.get()}")
            }

            enabledExtensionNames.put(platformSurfaceExtension)
            enabledExtensionNames.put(stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME))
            utf8Exts.forEach { enabledExtensionNames.put(it) }
            enabledExtensionNames.flip()

            val enabledLayerNames = if(!wantsOpenGLSwapchain && validation) {
                val pointers = stack.callocPointer(defaultValidationLayers.size)
                defaultValidationLayers.forEach { pointers.put(stack.UTF8(it)) }
                pointers
            } else {
                stack.callocPointer(0)
            }

            enabledLayerNames.flip()

            val createInfo = VkInstanceCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pNext(NULL)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(enabledExtensionNames)
                .ppEnabledLayerNames(enabledLayerNames)

            val instance = VU.getPointer("Creating Vulkan instance",
                { vkCreateInstance(createInfo, null, this) }, {})

            VkInstance(instance, createInfo)
        }
    }

    private fun setupDebugging(instance: VkInstance, flags: Int, callback: VkDebugReportCallbackEXT): Long {
        val dbgCreateInfo = VkDebugReportCallbackCreateInfoEXT.calloc()
            .sType(VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT)
            .pNext(NULL)
            .pfnCallback(callback)
            .pUserData(NULL)
            .flags(flags)

        val pCallback = memAllocLong(1)

        return try {
            val err = vkCreateDebugReportCallbackEXT(instance, dbgCreateInfo, null, pCallback)
            val callbackHandle = pCallback.get(0)
            memFree(pCallback)
            dbgCreateInfo.free()
            if (err != VK_SUCCESS) {
                throw RuntimeException("Failed to create VkInstance: " + VU.translate(err))
            }

            callbackHandle
        } catch(e: NullPointerException) {
            -1
        }
    }

    private fun createVertexBuffers(device: VulkanDevice, node: Node, state: VulkanObjectState): VulkanObjectState {
        val n = node as HasGeometry
        val vertices = n.vertices.duplicate()
        val normals = n.normals.duplicate()
        var texcoords = n.texcoords.duplicate()
        val indices = n.indices.duplicate()

        if(vertices.remaining() == 0) {
            return state
        }

        if (texcoords.remaining() == 0 && node.instances.size > 0) {
            val buffer = je_calloc(1, 4L * vertices.remaining() / n.vertexSize * n.texcoordSize)

            if(buffer == null) {
                logger.error("Could not allocate texcoords buffer with ${4L * vertices.remaining() / n.vertexSize * n.texcoordSize} bytes for ${node.name}")
                return state
            } else {
                n.texcoords = buffer.asFloatBuffer()
                texcoords = n.texcoords.asReadOnlyBuffer()
            }
        }

        val vertexAllocationBytes: Long = 4L * (vertices.remaining() + normals.remaining() + texcoords.remaining())
        val indexAllocationBytes: Long = 4L * indices.remaining()
        val fullAllocationBytes: Long = vertexAllocationBytes + indexAllocationBytes

        val stridedBuffer = je_malloc(fullAllocationBytes)

        if(stridedBuffer == null) {
            logger.error("Allocation failed, skipping vertex buffer creation for ${node.name}.")
            return state
        }

        val fb = stridedBuffer.asFloatBuffer()
        val ib = stridedBuffer.asIntBuffer()

        state.vertexCount = vertices.remaining() / n.vertexSize
        logger.trace("${node.name} has ${vertices.remaining()} floats and ${texcoords.remaining() / n.texcoordSize} remaining")

        for (index in 0 until vertices.remaining() step 3) {
            fb.put(vertices.get())
            fb.put(vertices.get())
            fb.put(vertices.get())

            fb.put(normals.get())
            fb.put(normals.get())
            fb.put(normals.get())

            if (texcoords.remaining() > 0) {
                fb.put(texcoords.get())
                fb.put(texcoords.get())
            }
        }

        logger.trace("Adding {} bytes to strided buffer", indices.remaining() * 4)
        if (indices.remaining() > 0) {
            state.isIndexed = true
            ib.position(vertexAllocationBytes.toInt() / 4)

            for (index in 0 until indices.remaining()) {
                ib.put(indices.get())
            }
        }

        logger.trace("Strided buffer is now at {} bytes", stridedBuffer.remaining())

        val stagingBuffer = VulkanBuffer(device,
            fullAllocationBytes * 1L,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            wantAligned = false)

        stagingBuffer.copyFrom(stridedBuffer)

        val vertexIndexBuffer = state.vertexBuffers["vertex+index"]
        val vertexBuffer = if(vertexIndexBuffer != null && vertexIndexBuffer.size >= fullAllocationBytes) {
            logger.debug("Reusing existing vertex+index buffer for {} update", node.name)
            vertexIndexBuffer
        } else {
            logger.debug("Creating new vertex+index buffer for {} with {} bytes", node.name, fullAllocationBytes)
            geometryPool.createBuffer(fullAllocationBytes.toInt())
        }

        logger.debug("Using VulkanBuffer {} for vertex+index storage, offset={}", vertexBuffer.vulkanBuffer.toHexString(), vertexBuffer.bufferOffset)

        logger.debug("Initiating copy with 0->${vertexBuffer.bufferOffset}, size=$fullAllocationBytes")
        val copyRegion = VkBufferCopy.calloc(1)
            .srcOffset(0)
            .dstOffset(vertexBuffer.bufferOffset)
            .size(fullAllocationBytes * 1L)

        with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
            vkCmdCopyBuffer(this,
                stagingBuffer.vulkanBuffer,
                vertexBuffer.vulkanBuffer,
                copyRegion)
            this.endCommandBuffer(device, commandPools.Standard, queue, flush = true, dealloc = true)
        }

        copyRegion.free()

        state.vertexBuffers.put("vertex+index", vertexBuffer)?.run {
            // check if vertex buffer has been replaced, if yes, close the old one
            if(this != vertexBuffer) { close() }
        }
        state.indexOffset = vertexBuffer.bufferOffset + vertexAllocationBytes
        state.indexCount = n.indices.remaining()

        je_free(stridedBuffer)
        stagingBuffer.close()

        return state
    }

    private fun updateInstanceBuffer(device: VulkanDevice, parentNode: Node, state: VulkanObjectState): VulkanObjectState {
        logger.trace("Updating instance buffer for ${parentNode.name}")

        // parentNode.instances is a CopyOnWrite array list, and here we keep a reference to the original.
        // If it changes in the meantime, no problemo.
        val instances = parentNode.instances
        val cam = scene.findObserver() ?: return state

        if (instances.isEmpty()) {
            logger.debug("$parentNode has no child instances attached, returning.")
            return state
        }

        // first we create a fake UBO to gauge the size of the needed properties
        val ubo = VulkanUBO(device)
        ubo.fromInstance(instances.first())

        val instanceBufferSize = ubo.getSize() * instances.size

        val instanceStagingBuffer = state.vertexBuffers["instanceStaging"]
        val stagingBuffer = if(instanceStagingBuffer != null && instanceStagingBuffer.size >= instanceBufferSize) {
            instanceStagingBuffer
        } else {
            logger.debug("Creating new staging buffer")
            val buffer = VulkanBuffer(device,
                (1.2 * instanceBufferSize).toLong(),
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                wantAligned = true)

            state.vertexBuffers["instanceStaging"] = buffer
            buffer
        }

        ubo.updateBackingBuffer(stagingBuffer)
        ubo.createUniformBuffer()

        val index = AtomicInteger(0)
        instances.parallelStream().forEach { node ->
            if(node.visible) {
                node.updateWorld(true, false)

                stagingBuffer.stagingBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).run {
                    ubo.populateParallel(this, offset = index.getAndIncrement() * ubo.getSize() * 1L, elements = node.instancedProperties)
                }
            }
        }

        stagingBuffer.stagingBuffer.position(stagingBuffer.stagingBuffer.limit())
        stagingBuffer.copyFromStagingBuffer()

        val existingInstanceBuffer = state.vertexBuffers["instance"]
        val instanceBuffer = if (existingInstanceBuffer != null
            && existingInstanceBuffer.size >= instanceBufferSize
            && existingInstanceBuffer.size < 1.5*instanceBufferSize) {
            existingInstanceBuffer
        } else {
            logger.debug("Instance buffer for ${parentNode.name} needs to be reallocated due to insufficient size ($instanceBufferSize vs ${state.vertexBuffers["instance"]?.size ?: "<not allocated yet>"})")
            state.vertexBuffers["instance"]?.close()

            val buffer = VulkanBuffer(device,
                instanceBufferSize * 1L,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                wantAligned = true)

            state.vertexBuffers["instance"] = buffer
            buffer
        }

        with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
            val copyRegion = VkBufferCopy.calloc(1)
                .size(instanceBufferSize * 1L)

            vkCmdCopyBuffer(this,
                stagingBuffer.vulkanBuffer,
                instanceBuffer.vulkanBuffer,
                copyRegion)

            copyRegion.free()
            this.endCommandBuffer(device, commandPools.Standard, queue, flush = true, dealloc = true)
        }

        state.instanceCount = index.get()//instances.size

        return state
    }

    private fun createDescriptorPool(device: VulkanDevice): Long {
        return stackPush().use { stack ->
            // We need to tell the API the number of max. requested descriptors per type
            val typeCounts = VkDescriptorPoolSize.callocStack(4, stack)
            typeCounts[0]
                .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(MAX_TEXTURES)

            typeCounts[1]
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                .descriptorCount(MAX_UBOS)

            typeCounts[2]
                .type(VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT)
                .descriptorCount(MAX_INPUT_ATTACHMENTS)

            typeCounts[3]
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(MAX_UBOS)

            // Create the global descriptor pool
            // All descriptors used in this example are allocated from this pool
            val descriptorPoolInfo = VkDescriptorPoolCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pNext(NULL)
                .pPoolSizes(typeCounts)
                .maxSets(MAX_TEXTURES + MAX_UBOS + MAX_INPUT_ATTACHMENTS + MAX_UBOS)// Set the max. number of sets that can be requested
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)

            val descriptorPool = VU.getLong("vkCreateDescriptorPool",
                { vkCreateDescriptorPool(device.vulkanDevice, descriptorPoolInfo, null, this) }, {})

            descriptorPool
        }
    }

    private fun prepareDefaultBuffers(device: VulkanDevice): DefaultBuffers {
        logger.debug("Creating buffers")
        return DefaultBuffers(
            UBOs = VulkanBuffer(device,
                512 * 1024 * 10,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                wantAligned = true),

            LightParameters = VulkanBuffer(device,
                512 * 1024 * 10,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                wantAligned = true),

            VRParameters = VulkanBuffer(device,
                256 * 10,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                wantAligned = true),

            ShaderProperties = VulkanBuffer(device,
                1024 * 1024,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                wantAligned = true))
    }

    private fun Node.rendererMetadata(): VulkanObjectState? {
        return this.metadata["VulkanRenderer"] as? VulkanObjectState
    }

    private fun recordSceneRenderCommands(pass: VulkanRenderpass,
                                          commandBuffer: VulkanCommandBuffer, sceneObjects: Deferred<List<Node>>,
                                          customNodeFilter: ((Node) -> Boolean)? = null, forceRerecording: Boolean = false) = runBlocking {
        val target = pass.getOutput()

        logger.trace("Initialising recording of scene command buffer for {}/{} ({} attachments)", pass.name, target, target.attachments.count())

        pass.vulkanMetadata.renderPassBeginInfo
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
            .pNext(NULL)
            .renderPass(target.renderPass.get(0))
            .framebuffer(target.framebuffer.get(0))
            .renderArea(pass.vulkanMetadata.renderArea)
            .pClearValues(pass.vulkanMetadata.clearValues)

        val renderOrderList = ArrayList<Node>(pass.vulkanMetadata.renderLists[commandBuffer]?.size ?: 512)

        // here we discover all the nodes which are relevant for this pass,
        // e.g. which have the same transparency settings as the pass,
        // and filter according to any custom filters applicable to this pass
        // (e.g. to discern geometry from lighting passes)
        sceneObjects.await().filter { customNodeFilter?.invoke(it) ?: true }.forEach { n ->
            n.rendererMetadata()?.let {
                if (!((pass.passConfig.renderOpaque && n.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) ||
                        (pass.passConfig.renderTransparent && !n.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent))) {
                    renderOrderList.add(n)
                } else {
                    return@let
                }
            }
        }

        // if the pass' metadata does not contain a command buffer,
        // OR the cached command buffer does not contain the same nodes in the same order,
        // OR re-recording is forced due to node changes, the buffer will be re-recorded.
        // Furthermore, all sibling command buffers for this pass will be marked stale, thus
        // also forcing their re-recording.
        if(!pass.vulkanMetadata.renderLists.containsKey(commandBuffer)
            || !renderOrderList.toTypedArray().contentDeepEquals(pass.vulkanMetadata.renderLists.getValue(commandBuffer))
            || forceRerecording) {

            pass.vulkanMetadata.renderLists[commandBuffer] = renderOrderList.toTypedArray()
            pass.vulkanMetadata.renderLists.keys.forEach { it.stale = true }

            // if we are in a VR pass, invalidate passes for both eyes to prevent one of them housing stale data
            if(renderConfig.stereoEnabled && (pass.name.contains("Left") || pass.name.contains("Right"))) {
                val passLeft = if(pass.name.contains("Left")) {
                    pass.name
                } else {
                    pass.name.substringBefore("Right") + "Left"
                }

                val passRight = if(pass.name.contains("Right")) {
                    pass.name
                } else {
                    pass.name.substringBefore("Left") + "Right"
                }

                renderpasses[passLeft]?.vulkanMetadata?.renderLists?.keys?.forEach { it.stale = true }
                renderpasses[passRight]?.vulkanMetadata?.renderLists?.keys?.forEach { it.stale = true }
            }
        }

        // If the command buffer is not stale, though, we keep the cached one and return. This
        // can buy quite a bit of performance.
        if(!commandBuffer.stale && commandBuffer.commandBuffer != null) {
            return@runBlocking
        }

        logger.debug("Recording scene command buffer $commandBuffer for pass ${pass.name}...")

        // command buffer cannot be null here anymore, otherwise this is clearly in error
        with(commandBuffer.prepareAndStartRecording(commandPools.Render)) {

            vkCmdResetQueryPool(this, timestampQueryPool, 2*renderpasses.values.indexOf(pass), 2)
            vkCmdWriteTimestamp(this, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                timestampQueryPool, 2*renderpasses.values.indexOf(pass))

            if(pass.passConfig.blitInputs) {
                stackPush().use { stack ->
                    val imageBlit = VkImageBlit.callocStack(1, stack)

                    for((name, input) in pass.inputs) {
                        val attachmentList = if(name.contains(".")) {
                            input.attachments.filter { it.key == name.substringAfter(".") }
                        } else {
                            input.attachments
                        }

                        for((_, inputAttachment) in attachmentList) {

                            val type = when(inputAttachment.type) {
                                VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK_IMAGE_ASPECT_COLOR_BIT
                                VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK_IMAGE_ASPECT_DEPTH_BIT
                            }

                            // return to use() if no output with the correct attachment type is found
                            val outputAttachment = pass.getOutput().attachments.values.find { it.type == inputAttachment.type }
                            if(outputAttachment == null) {
                                logger.warn("Didn't find matching attachment for $name of type ${inputAttachment.type}")
                                return@use
                            }

                            val outputAspectSrcType = when(outputAttachment.type) {
                                VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            }

                            val outputAspectDstType = when(outputAttachment.type) {
                                VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                                VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            }

                            val inputAspectType = when(inputAttachment.type) {
                                VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            }

                            val outputDstStage = when(outputAttachment.type) {
                                VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                                VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                            }

                            val offsetX = (input.width * pass.passConfig.viewportOffset.first).toInt()
                            val offsetY = (input.height * pass.passConfig.viewportOffset.second).toInt()

                            val sizeX = offsetX + (input.width * pass.passConfig.viewportSize.first).toInt()
                            val sizeY = offsetY + (input.height * pass.passConfig.viewportSize.second).toInt()

                            imageBlit.srcSubresource().set(type, 0, 0, 1)
                            imageBlit.srcOffsets(0).set(offsetX, offsetY, 0)
                            imageBlit.srcOffsets(1).set(sizeX, sizeY, 1)

                            imageBlit.dstSubresource().set(type, 0, 0, 1)
                            imageBlit.dstOffsets(0).set(offsetX, offsetY, 0)
                            imageBlit.dstOffsets(1).set(sizeX, sizeY, 1)

                            val transitionBuffer = this@with

                            val subresourceRange = VkImageSubresourceRange.callocStack(stack)
                                .aspectMask(type)
                                .baseMipLevel(0)
                                .levelCount(1)
                                .baseArrayLayer(0)
                                .layerCount(1)

                            // transition source attachment
                            VulkanTexture.transitionLayout(inputAttachment.image,
                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                subresourceRange = subresourceRange,
                                commandBuffer = transitionBuffer,
                                srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                            )

                            // transition destination attachment
                            VulkanTexture.transitionLayout(outputAttachment.image,
                                inputAspectType,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                subresourceRange = subresourceRange,
                                commandBuffer = transitionBuffer,
                                srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                            )

                            vkCmdBlitImage(this@with,
                                inputAttachment.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                outputAttachment.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                imageBlit, VK_FILTER_NEAREST
                            )

                            // transition destination attachment back to attachment
                            VulkanTexture.transitionLayout(outputAttachment.image,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                outputAspectDstType,
                                subresourceRange = subresourceRange,
                                commandBuffer = transitionBuffer,
                                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                                dstStage = outputDstStage
                            )

                            // transition source attachment back to shader read-only
                            VulkanTexture.transitionLayout(inputAttachment.image,
                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                outputAspectSrcType,
                                subresourceRange = subresourceRange,
                                commandBuffer = transitionBuffer,
                                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                                dstStage = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            )

                        }
                    }
                }
            }

            vkCmdBeginRenderPass(this, pass.vulkanMetadata.renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE)

            vkCmdSetViewport(this, 0, pass.vulkanMetadata.viewport)
            vkCmdSetScissor(this, 0, pass.vulkanMetadata.scissor)

            // allocate more vertexBufferOffsets than needed, set limit lateron
            pass.vulkanMetadata.uboOffsets.limit(16)
            (0 until pass.vulkanMetadata.uboOffsets.limit()).forEach { pass.vulkanMetadata.uboOffsets.put(it, 0) }

            var previousPipeline: Pipeline? = null
            renderOrderList.forEach drawLoop@ { node ->
                val s = node.rendererMetadata() ?: return@drawLoop

                // instanced nodes will not be drawn directly, but only the master node.
                // nodes with no vertices will also not be drawn.
                if(s.vertexCount == 0) {
                    return@drawLoop
                }

                // return if we are on a opaque pass, but the node requires transparency.
                if(pass.passConfig.renderOpaque && node.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                    return@drawLoop
                }

                // return if we are on a transparency pass, but the node is only opaque.
                if(pass.passConfig.renderTransparent && !node.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                    return@drawLoop
                }

                val vertexIndexBuffer = s.vertexBuffers["vertex+index"]
                val instanceBuffer = s.vertexBuffers["instance"]

                if(vertexIndexBuffer == null) {
                    logger.error("Vertex+Index buffer not initialiazed")
                    return@drawLoop
                }

                logger.trace("{} - Rendering {}, vertex+index buffer={}...", pass.name, node.name, vertexIndexBuffer.vulkanBuffer.toHexString())
//                if(rerecordingCauses.contains(node.name)) {
//                    logger.debug("Using pipeline ${pass.getActivePipeline(node)} for re-recording")
//                }
                val p = pass.getActivePipeline(node)
                val pipeline = p.getPipelineForGeometryType((node as HasGeometry).geometryType)
                val specs = p.orderedDescriptorSpecs()

                if(pipeline != previousPipeline) {
                    vkCmdBindPipeline(this, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)
                    previousPipeline = pipeline
                }

                if(logger.isTraceEnabled) {
                    logger.trace("node {} has: {} / pipeline needs: {}", node.name, s.UBOs.keys.joinToString(", "), specs.joinToString { it.key })
                }

                pass.vulkanMetadata.descriptorSets.rewind()
                pass.vulkanMetadata.uboOffsets.rewind()

                pass.vulkanMetadata.vertexBufferOffsets.put(0, vertexIndexBuffer.bufferOffset)
                pass.vulkanMetadata.vertexBuffers.put(0, vertexIndexBuffer.vulkanBuffer)

                pass.vulkanMetadata.vertexBufferOffsets.limit(1)
                pass.vulkanMetadata.vertexBuffers.limit(1)

                if(node.instancedProperties.size > 0) {
                    if (node.instances.size > 0 && instanceBuffer != null) {
                        pass.vulkanMetadata.vertexBuffers.limit(2)
                        pass.vulkanMetadata.vertexBufferOffsets.limit(2)

                        pass.vulkanMetadata.vertexBufferOffsets.put(1, 0)
                        pass.vulkanMetadata.vertexBuffers.put(1, instanceBuffer.vulkanBuffer)
                    } else {
                        return@drawLoop
                    }
                }

                val sets = specs.mapNotNull { (name, _) ->
                    val ds = when {
                        name == "VRParameters" -> {
                            DescriptorSet.setOrNull(descriptorSets["VRParameters"], setName = "VRParameters")
                        }

                        name == "LightParameters" -> {
                            DescriptorSet.setOrNull(descriptorSets["LightParameters"], setName = "LightParameters")
                        }

                        name.startsWith("Inputs") -> {
                            DescriptorSet.setOrNull(pass.descriptorSets["input-${pass.name}-${name.substringAfter("-")}"], setName = "Inputs")
                        }

                        name == "ShaderParameters" -> {
                            DescriptorSet.setOrNull(pass.descriptorSets["ShaderParameters-${pass.name}"], setName = "ShaderParameters")
                        }

                        else -> {
                            when {
                                s.UBOs.containsKey(name) ->
                                    DescriptorSet.DynamicSet(s.UBOs.getValue(name).first, offset = s.UBOs.getValue(name).second.offsets.get(0), setName = name)
                                s.UBOs.containsKey("${pass.name}-$name") ->
                                    DescriptorSet.DynamicSet(s.UBOs.getValue("${pass.name}-$name").first, offset = s.UBOs.getValue("${pass.name}-$name").second.offsets.get(0), setName = name)
                                s.getTextureDescriptorSet(pass.passConfig.type.name, name) != null ->
                                    DescriptorSet.setOrNull(s.getTextureDescriptorSet(pass.passConfig.type.name, name), name)
                                else -> DescriptorSet.None
                            }
                        }
                    }

                    if(ds == null || ds == DescriptorSet.None) {
                        logger.error("Internal consistency error for node ${node.name}: Descriptor set $name not found in renderpass, skipping node for rendering.")
                        return@drawLoop
                    }

                    ds
                }

                if(logger.isDebugEnabled) {
                    logger.debug("${node.name} requires DS ${specs.joinToString { "${it.key}, " }}")
                }

                val requiredSets = sets.filter { it !is DescriptorSet.None }.map { it.id }.toLongArray()
                if(pass.vulkanMetadata.descriptorSets.capacity() < requiredSets.size) {
                    logger.debug("Reallocating descriptor set storage")
                    memFree(pass.vulkanMetadata.descriptorSets)
                    pass.vulkanMetadata.descriptorSets = memAllocLong(requiredSets.size)
                }

                pass.vulkanMetadata.descriptorSets.position(0)
                pass.vulkanMetadata.descriptorSets.limit(pass.vulkanMetadata.descriptorSets.capacity())
                pass.vulkanMetadata.descriptorSets.put(requiredSets)
                pass.vulkanMetadata.descriptorSets.flip()

                pass.vulkanMetadata.uboOffsets.position(0)
                pass.vulkanMetadata.uboOffsets.limit(pass.vulkanMetadata.uboOffsets.capacity())
                pass.vulkanMetadata.uboOffsets.put(sets.filter { it is DescriptorSet.DynamicSet }.map { (it as DescriptorSet.DynamicSet).offset }.toIntArray())
                pass.vulkanMetadata.uboOffsets.flip()

                if(p.pushConstantSpecs.containsKey("currentEye")) {
                    vkCmdPushConstants(this, pipeline.layout, VK_SHADER_STAGE_ALL, 0, pass.vulkanMetadata.eye)
                }

                vkCmdBindPipeline(this, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)
                if(pass.vulkanMetadata.descriptorSets.limit() > 0) {
                    vkCmdBindDescriptorSets(this, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.layout, 0, pass.vulkanMetadata.descriptorSets, pass.vulkanMetadata.uboOffsets)
                }
                vkCmdBindVertexBuffers(this, 0, pass.vulkanMetadata.vertexBuffers, pass.vulkanMetadata.vertexBufferOffsets)

                logger.debug("${pass.name}: now drawing {}, {} DS bound, {} textures, {} vertices, {} indices, {} instances", node.name, pass.vulkanMetadata.descriptorSets.limit(), s.textures.count(), s.vertexCount, s.indexCount, s.instanceCount)

                if (s.isIndexed) {
                    vkCmdBindIndexBuffer(this, pass.vulkanMetadata.vertexBuffers.get(0), s.indexOffset, VK_INDEX_TYPE_UINT32)
                    vkCmdDrawIndexed(this, s.indexCount, s.instanceCount, 0, 0, 0)
                } else {
                    vkCmdDraw(this, s.vertexCount, s.instanceCount, 0, 0)
                }
            }

            vkCmdEndRenderPass(this)

            vkCmdWriteTimestamp(this, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                timestampQueryPool, 2*renderpasses.values.indexOf(pass)+1)

            // finish command buffer recording by marking this buffer non-stale
            commandBuffer.stale = false
            this.endCommandBuffer()
        }
    }

    private fun recordPostprocessRenderCommands(pass: VulkanRenderpass, commandBuffer: VulkanCommandBuffer) {
        val target = pass.getOutput()

        logger.trace("Creating postprocessing command buffer for {}/{} ({} attachments)", pass.name, target, target.attachments.count())

        pass.vulkanMetadata.renderPassBeginInfo
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
            .pNext(NULL)
            .renderPass(target.renderPass.get(0))
            .framebuffer(target.framebuffer.get(0))
            .renderArea(pass.vulkanMetadata.renderArea)
            .pClearValues(pass.vulkanMetadata.clearValues)

        if(!commandBuffer.stale) {
            return
        }

        // prepare command buffer and start recording
        with(commandBuffer.prepareAndStartRecording(commandPools.Render)) {

            vkCmdResetQueryPool(this, timestampQueryPool, 2*renderpasses.values.indexOf(pass), 2)
            vkCmdWriteTimestamp(this, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                timestampQueryPool, 2*renderpasses.values.indexOf(pass))
            vkCmdBeginRenderPass(this, pass.vulkanMetadata.renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE)

            vkCmdSetViewport(this, 0, pass.vulkanMetadata.viewport)
            vkCmdSetScissor(this, 0, pass.vulkanMetadata.scissor)

            val pipeline = pass.getDefaultPipeline()
            val vulkanPipeline = pipeline.getPipelineForGeometryType(GeometryType.TRIANGLES)

            if (pass.vulkanMetadata.descriptorSets.capacity() != pipeline.descriptorSpecs.count()) {
                memFree(pass.vulkanMetadata.descriptorSets)
                pass.vulkanMetadata.descriptorSets = memAllocLong(pipeline.descriptorSpecs.count())
            }

            // allocate more vertexBufferOffsets than needed, set limit lateron
            pass.vulkanMetadata.uboOffsets.position(0)
            pass.vulkanMetadata.uboOffsets.limit(16)
            (0..15).forEach { pass.vulkanMetadata.uboOffsets.put(it, 0) }

            if (logger.isDebugEnabled) {
                logger.debug("${pass.name}: descriptor sets are {}", pass.descriptorSets.keys.joinToString(", "))
                logger.debug("pipeline provides {}", pipeline.descriptorSpecs.keys.joinToString(", "))
            }

            // set the required descriptor sets for this render pass
            pass.vulkanMetadata.setRequiredDescriptorSetsPostprocess(pass, pipeline)

            if(pipeline.pushConstantSpecs.containsKey("currentEye")) {
                vkCmdPushConstants(this, vulkanPipeline.layout, VK_SHADER_STAGE_ALL, 0, pass.vulkanMetadata.eye)
            }

            vkCmdBindPipeline(this, VK_PIPELINE_BIND_POINT_GRAPHICS, vulkanPipeline.pipeline)
            if(pass.vulkanMetadata.descriptorSets.limit() > 0) {
                logger.debug("Binding ${pass.vulkanMetadata.descriptorSets.limit()} descriptor sets with ${pass.vulkanMetadata.uboOffsets.limit()} required offsets")
                vkCmdBindDescriptorSets(this, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    vulkanPipeline.layout, 0, pass.vulkanMetadata.descriptorSets, pass.vulkanMetadata.uboOffsets)
            }

            vkCmdDraw(this, 3, 1, 0, 0)

            vkCmdEndRenderPass(this)
            vkCmdWriteTimestamp(this, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                timestampQueryPool, 2*renderpasses.values.indexOf(pass)+1)

            commandBuffer.stale = false
            this.endCommandBuffer()
        }
    }

    private fun VulkanRenderpass.VulkanMetadata.setRequiredDescriptorSetsPostprocess(pass: VulkanRenderpass, pipeline: VulkanPipeline): Int {
        var requiredDynamicOffsets = 0
        logger.trace("Ubo position: {}", this.uboOffsets.position())

        pipeline.descriptorSpecs.entries.sortedBy { it.value.set }.forEachIndexed { i, (name, spec) ->
            logger.trace("Looking at {}, set={}, binding={}...", name, spec.set, spec.binding)
            val dsName = when {
                name.startsWith("ShaderParameters") -> "ShaderParameters-${pass.name}"
                name.startsWith("Inputs") -> "input-${pass.name}-${spec.set}"
                name.startsWith("Matrices") -> {
                    val offsets = sceneUBOs.first().rendererMetadata()!!.UBOs["Matrices"]!!.second.offsets
                    this.uboOffsets.put(offsets)
                    requiredDynamicOffsets += 3

                    "Matrices"
                }
                else -> name
            }

            val set = if (dsName == "Matrices" || dsName == "LightParameters" || dsName == "VRParameters") {
                this@VulkanRenderer.descriptorSets[dsName]
            } else {
                pass.descriptorSets[dsName]
            }

            if (set != null) {
                logger.trace("Adding DS#{} for {} to required pipeline DSs", i, dsName)
                this.descriptorSets.put(i, set)
            } else {
                logger.error("DS for {} not found!", dsName)
            }
        }

        logger.trace("{}: Requires {} dynamic offsets", pass.name, requiredDynamicOffsets)
        this.uboOffsets.flip()

        return requiredDynamicOffsets
    }

    private fun updateInstanceBuffers(sceneObjects: Deferred<List<Node>>) = runBlocking {
        val instanceMasters = sceneObjects.await().filter { it.instances.size > 0 }

        instanceMasters.forEach { parent ->
            val metadata = parent.rendererMetadata()

            if(metadata != null && metadata.initialized) {
                updateInstanceBuffer(device, parent, parent.rendererMetadata()!!)
            }
        }

        instanceMasters.isNotEmpty()
    }

    fun GLMatrix.applyVulkanCoordinateSystem(): GLMatrix {
        val m = vulkanProjectionFix.clone()
        m.mult(this)

        return m
    }

    private fun Display.wantsVR(): Display? {
        return if (settings.get("vr.Active")) {
            this@wantsVR
        } else {
            null
        }
    }

    private fun getDescriptorCache(): ConcurrentHashMap<String, Long> {
        @Suppress("UNCHECKED_CAST")
        return scene.metadata.getOrPut("DescriptorCache") {
            ConcurrentHashMap<String, Long>()
        } as? ConcurrentHashMap<String, Long> ?: throw IllegalStateException("Could not retrieve descriptor cache from scene")
    }

    private fun updateDefaultUBOs(device: VulkanDevice): Boolean = runBlocking {
        if(shouldClose) {
            return@runBlocking false
        }

        logger.trace("Updating default UBOs for {}", device)
        // find observer, if none, return
        val cam = scene.findObserver() ?: return@runBlocking false
        // sticky boolean
        var updated: Boolean by StickyBoolean(initial = false)

        if (!cam.lock.tryLock()) {
            return@runBlocking false
        }

        val hmd = hub?.getWorkingHMDDisplay()?.wantsVR()

        cam.view = cam.getTransformation()
        cam.updateWorld(true, false)

        buffers.VRParameters.reset()
        val vrUbo = defaultUBOs["VRParameters"]!!
        vrUbo.add("projection0", {
            (hmd?.getEyeProjection(0, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection).applyVulkanCoordinateSystem()
        })
        vrUbo.add("projection1", {
            (hmd?.getEyeProjection(1, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection).applyVulkanCoordinateSystem()
        })
        vrUbo.add("inverseProjection0", {
            (hmd?.getEyeProjection(0, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection).applyVulkanCoordinateSystem().inverse
        })
        vrUbo.add("inverseProjection1", {
            (hmd?.getEyeProjection(1, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection).applyVulkanCoordinateSystem().inverse
        })
        vrUbo.add("headShift", { hmd?.getHeadToEyeTransform(0) ?: GLMatrix.getIdentity() })
        vrUbo.add("IPD", { hmd?.getIPD() ?: 0.05f })
        vrUbo.add("stereoEnabled", { renderConfig.stereoEnabled.toInt() })

        updated = vrUbo.populate()

        buffers.UBOs.reset()
        buffers.ShaderProperties.reset()

        sceneUBOs.forEach { node ->
            node.lock.withLock {
                var nodeUpdated: Boolean by StickyBoolean(initial = false)

                if (!node.metadata.containsKey("VulkanRenderer")) {
                    return@forEach
                }

                val s = node.rendererMetadata() ?: return@forEach

                val ubo = s.UBOs["Matrices"]!!.second

                node.updateWorld(true, false)

                ubo.offsets.limit(1)

                var bufferOffset = ubo.backingBuffer!!.advance()
                ubo.offsets.put(0, bufferOffset)
                ubo.offsets.limit(1)

//                node.projection.copyFrom(cam.projection.applyVulkanCoordinateSystem())

                node.view.copyFrom(cam.view)

                nodeUpdated = ubo.populate(offset = bufferOffset.toLong())

                val materialUbo = s.UBOs["MaterialProperties"]!!.second
                bufferOffset = ubo.backingBuffer!!.advance()
                materialUbo.offsets.put(0, bufferOffset)
                materialUbo.offsets.limit(1)

                nodeUpdated = materialUbo.populate(offset = bufferOffset.toLong())

                s.UBOs.filter { it.key.contains("ShaderProperties") && it.value.second.memberCount() > 0 }.forEach {
//                if(s.requiredDescriptorSets.keys.any { it.contains("ShaderProperties") }) {
                    val propertyUbo = it.value.second
                    val offset = propertyUbo.backingBuffer!!.advance()
                    nodeUpdated = propertyUbo.populate(offset = offset.toLong())
                    propertyUbo.offsets.put(0, offset)
                    propertyUbo.offsets.limit(1)
                }

                if(nodeUpdated && node.getScene()?.onNodePropertiesChanged?.isNotEmpty() == true) {
                    GlobalScope.launch { node.getScene()?.onNodePropertiesChanged?.forEach { it.value.invoke(node) } }
                }

                updated = nodeUpdated
            }
        }

        buffers.UBOs.copyFromStagingBuffer()

        val lightUbo = defaultUBOs["LightParameters"]!!
        lightUbo.add("ViewMatrix0", { cam.getTransformationForEye(0) })
        lightUbo.add("ViewMatrix1", { cam.getTransformationForEye(1) })
        lightUbo.add("InverseViewMatrix0", { cam.getTransformationForEye(0).inverse })
        lightUbo.add("InverseViewMatrix1", { cam.getTransformationForEye(1).inverse })
        lightUbo.add("ProjectionMatrix", { cam.projection.applyVulkanCoordinateSystem() })
        lightUbo.add("InverseProjectionMatrix", { cam.projection.applyVulkanCoordinateSystem().inverse })
        lightUbo.add("CamPosition", { cam.position })

        updated = lightUbo.populate()

        buffers.ShaderProperties.copyFromStagingBuffer()

//        updateDescriptorSets()

        cam.lock.unlock()

        return@runBlocking updated
    }

    @Suppress("UNUSED")
    override fun screenshot(filename: String, overwrite: Boolean) {
        screenshotRequested = true
        screenshotOverwriteExisting = overwrite
        screenshotFilename = filename
    }

    fun Int.toggle(): Int {
        if (this == 0) {
            return 1
        } else if (this == 1) {
            return 0
        }

        logger.warn("Property is not togglable.")
        return this
    }

    @Suppress("UNUSED")
    fun toggleDebug() {
        settings.getAllSettings().forEach {
            if (it.toLowerCase().contains("debug")) {
                try {
                    val property = settings.get<Int>(it).toggle()
                    settings.set(it, property)

                } catch(e: Exception) {
                    logger.warn("$it is a property that is not togglable.")
                }
            }
        }
    }

    /**
     * Closes the current instance of [VulkanRenderer].
     */
    override fun close() {
        shouldClose = true
    }

    fun closeInternal() {
        if(!initialized) {
            return
        }

        initialized = false

        logger.info("Renderer teardown started.")
        vkQueueWaitIdle(queue)

        logger.debug("Cleaning texture cache...")
        textureCache.forEach {
            logger.debug("Cleaning ${it.key}...")
            it.value.close()
        }

        logger.debug("Closing nodes...")
        scene.discover(scene, { true }).forEach {
            destroyNode(it)
        }
        scene.metadata.remove("DescriptorCache")
        scene.initialized = false

        logger.debug("Closing buffers...")
        buffers.LightParameters.close()
        buffers.ShaderProperties.close()
        buffers.UBOs.close()
        buffers.VRParameters.close()

        logger.debug("Closing default UBOs...")
        defaultUBOs.forEach { ubo ->
            ubo.value.close()
        }

        logger.debug("Closing memory pools ...")
        geometryPool.close()

        logger.debug("Closing vertex descriptors ...")
        vertexDescriptors.forEach {
            logger.debug("Closing vertex descriptor ${it.key}...")

            it.value.attributeDescription?.free()
            it.value.bindingDescription?.free()

            it.value.state.free()
        }

        logger.debug("Closing descriptor sets and pools...")
        descriptorSetLayouts.forEach { vkDestroyDescriptorSetLayout(device.vulkanDevice, it.value, null) }
        vkDestroyDescriptorPool(device.vulkanDevice, descriptorPool, null)

        logger.debug("Closing command buffers...")
        ph.commandBuffers.free()
        memFree(ph.signalSemaphore)
        memFree(ph.waitSemaphore)
        memFree(ph.waitStages)

        if(timestampQueryPool != -1L) {
            logger.debug("Closing query pools...")
            vkDestroyQueryPool(device.vulkanDevice, timestampQueryPool, null)
        }

        semaphores.forEach { it.value.forEach { semaphore -> vkDestroySemaphore(device.vulkanDevice, semaphore, null) } }

        memFree(firstWaitSemaphore)
        semaphoreCreateInfo.free()

        logger.debug("Closing swapchain...")

        swapchain.close()

        logger.debug("Closing renderpasses...")
        renderpasses.forEach { _, vulkanRenderpass -> vulkanRenderpass.close() }

        logger.debug("Clearing shader module cache...")
        VulkanShaderModule.clearCache()

        logger.debug("Closing command pools...")
        with(commandPools) {
            device.destroyCommandPool(Render)
            device.destroyCommandPool(Compute)
            device.destroyCommandPool(Standard)
            device.destroyCommandPool(Transfer)
        }

        vkDestroyPipelineCache(device.vulkanDevice, pipelineCache, null)

        if (validation && debugCallbackHandle != -1L) {
            vkDestroyDebugReportCallbackEXT(instance, debugCallbackHandle, null)
        }

        debugCallback.free()

        logger.debug("Closing device $device...")
        device.close()

        logger.debug("Closing instance...")
        vkDestroyInstance(instance, null)

        heartbeatTimer.cancel()
        heartbeatTimer.purge()
        logger.info("Renderer teardown complete.")
    }

    override fun reshape(newWidth: Int, newHeight: Int) {

    }

    @Suppress("UNUSED")
    fun toggleFullscreen() {
        toggleFullscreen = !toggleFullscreen
    }

    fun switchFullscreen() {
        hub?.let { hub -> swapchain.toggleFullscreen(hub, swapchainRecreator) }
    }

    /**
     * Sets the rendering quality, if the loaded renderer config file supports it.
     *
     * @param[quality] The [RenderConfigReader.RenderingQuality] to be set.
     */
    override fun setRenderingQuality(quality: RenderConfigReader.RenderingQuality) {
        fun setConfigSetting(key: String, value: Any) {
            val setting = "Renderer.$key"

            logger.debug("Setting $setting: ${settings.get<Any>(setting)} -> $value")
            settings.set(setting, value)
        }

        if(renderConfig.qualitySettings.isNotEmpty()) {
            logger.info("Setting rendering quality to $quality")

            renderConfig.qualitySettings[quality]?.forEach { setting ->
                if(setting.key.endsWith(".shaders") && setting.value is List<*>) {
                    val pass = setting.key.substringBeforeLast(".shaders")
                    @Suppress("UNCHECKED_CAST") val shaders = setting.value as? List<String> ?: return@forEach

                    renderConfig.renderpasses[pass]?.shaders = shaders

                    @Suppress("SENSELESS_COMPARISON")
                    if(swapchainRecreator != null) {
                        swapchainRecreator.mustRecreate = true
                        swapchainRecreator.afterRecreateHook = { swapchainRecreator ->
                            renderConfig.qualitySettings[quality]?.filter { !it.key.endsWith(".shaders") }?.forEach {
                                setConfigSetting(it.key, it.value)
                            }

                            swapchainRecreator.afterRecreateHook = {}
                        }
                    }
                } else {
                    setConfigSetting(setting.key, setting.value)
                }
            }
        } else {
            logger.warn("The current renderer config, $renderConfigFile, does not support setting quality options.")
        }
    }
}
