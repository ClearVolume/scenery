package graphics.scenery.backends.opengl

import cleargl.*
import com.jogamp.nativewindow.WindowClosingProtocol
import com.jogamp.newt.event.WindowAdapter
import com.jogamp.newt.event.WindowEvent
import com.jogamp.opengl.*
import com.jogamp.opengl.util.Animator
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil
import graphics.scenery.*
import graphics.scenery.backends.*
import graphics.scenery.spirvcrossj.Loader
import graphics.scenery.spirvcrossj.libspirvcrossj
import graphics.scenery.textures.Texture
import graphics.scenery.textures.Texture.BorderColor
import graphics.scenery.textures.Texture.RepeatMode
import graphics.scenery.textures.UpdatableTexture
import graphics.scenery.utils.*
import kotlinx.coroutines.*
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.Platform
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.image.DataBufferInt
import java.io.File
import java.io.FileOutputStream
import java.lang.Math
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import kotlin.collections.LinkedHashMap
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Deferred Lighting Renderer for scenery
 *
 * This is the main class of scenery's Deferred Lighting Renderer. Currently,
 * a rendering strategy using a 32bit position, 16bit normal, 32bit RGBA diffuse/albedo,
 * and 24bit depth buffer is employed. The renderer supports HDR rendering and does that
 * by default. By deactivating the `hdr.Active` [Settings], HDR can be programmatically
 * deactivated. The renderer also supports drawing to HMDs via OpenVR. If this is intended,
 * make sure the `vr.Active` [Settings] is set to `true`, and that the `Hub` has a HMD
 * instance attached.
 *
 * @param[hub] Hub instance to use and attach to.
 * @param[applicationName] The name of this application.
 * @param[scene] The [Scene] instance to initialize first.
 * @param[width] Horizontal window size.
 * @param[height] Vertical window size.
 * @param[embedIn] An optional [SceneryPanel] in which to embed the renderer instance.
 * @param[renderConfigFile] The file to create a [RenderConfigReader.RenderConfig] from.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

@Suppress("MemberVisibilityCanBePrivate")
open class OpenGLRenderer(hub: Hub,
                          applicationName: String,
                          scene: Scene,
                          width: Int,
                          height: Int,
                          renderConfigFile: String,
                          final override var embedIn: SceneryPanel? = null,
                          var embedInDrawable: GLAutoDrawable? = null) : Renderer(), Hubable, ClearGLEventListener {
    /** slf4j logger */
    private val logger by LazyLogger()
    private val className = this.javaClass.simpleName
    /** [GL4] instance handed over, coming from [ClearGLDefaultEventListener]*/
    private lateinit var gl: GL4
    /** should the window close on next looping? */
    @Volatile override var shouldClose = false
    /** the scenery window */
    final override var window: SceneryWindow = SceneryWindow.UninitializedWindow()
    /** separately stored ClearGLWindow */
    var cglWindow: ClearGLWindow? = null
    /** drawble for offscreen rendering */
    var drawable: GLAutoDrawable? = null
    /** Whether the renderer manages its own event loop, which is the case for this one. */
    override var managesRenderLoop = true

    /** The currently active scene */
    var scene: Scene = Scene()

    /** Cache of [Node]s, needed e.g. for fullscreen quad rendering */
    private var nodeStore = ConcurrentHashMap<String, Node>()

    /** [Settings] for the renderer */
    final override var settings: Settings = Settings()

    /** The hub used for communication between the components */
    final override var hub: Hub?

    private var textureCache = HashMap<String, GLTexture>()
    private var shaderPropertyCache = HashMap<Class<*>, List<Field>>()
    private var uboCache = ConcurrentHashMap<String, OpenGLUBO>()
    private var joglDrawable: GLAutoDrawable? = null
    private var screenshotRequested = false
    private var screenshotOverwriteExisting = false
    private var screenshotFilename = ""
    private var encoder: H264Encoder? = null
    private var recordMovie = false
    private var movieFilename = ""

    /**
     * Activate or deactivate push-based rendering mode (render only on scene changes
     * or input events). Push mode is activated if [pushMode] is true.
     */
    override var pushMode: Boolean = false

    private var updateLatch: CountDownLatch? = null

    private var lastResizeTimer = Timer()
    @Volatile private var mustRecreateFramebuffers = false
    private var framebufferRecreateHook: () -> Unit = {}
    private var gpuStats: GPUStats? = null
    private var maxTextureUnits = 8

    /** heartbeat timer */
    private var heartbeatTimer = Timer()
    override var lastFrameTime = System.nanoTime() * 1.0f
    private var currentTime = System.nanoTime()

    @Volatile override var initialized = false
    override var firstImageReady: Boolean = false
        protected set

    protected var frames = 0L
    var fps = 0
        protected set
    protected var framesPerSec = 0
    val pboCount = 2
    @Volatile private var pbos: IntArray = IntArray(pboCount) { 0 }
    private var pboBuffers: Array<ByteBuffer?> = Array(pboCount) { null }
    private var readIndex = 0
    private var updateIndex = 1

    private var renderCalled = false

    private var renderConfig: RenderConfigReader.RenderConfig
    final override var renderConfigFile = ""
        set(config) {
            field = config
            this.renderConfig = RenderConfigReader().loadFromFile(renderConfigFile)

            mustRecreateFramebuffers = true
        }

    private var renderpasses = LinkedHashMap<String, OpenGLRenderpass>()
    private var flow: List<String>

    /**
     * Extension function of Boolean to use Booleans in GLSL
     *
     * This function converts a Boolean to Int 0, if false, and to 1, if true
     */
    fun Boolean.toInt(): Int {
        return if (this) {
            1
        } else {
            0
        }
    }

    var applicationName = ""

    inner class OpenGLResizeHandler: ResizeHandler {
        @Volatile override var lastResize = -1L
        override var lastWidth = 0
        override var lastHeight = 0

        @Synchronized override fun queryResize() {
            if (lastWidth <= 0 || lastHeight <= 0) {
                lastWidth = Math.max(1, lastWidth)
                lastHeight = Math.max(1, lastHeight)
                return
            }

            if (lastResize > 0L && lastResize + WINDOW_RESIZE_TIMEOUT < System.nanoTime()) {
                lastResize = System.nanoTime()
                return
            }

            if (lastWidth == window.width && lastHeight == window.height) {
                return
            }

            mustRecreateFramebuffers = true
            gl.glDeleteBuffers(pboCount, pbos, 0)
            pbos = IntArray(pboCount) { 0 }

            lastWidth = window.width
            lastHeight = window.height

            if(drawable is GLOffscreenAutoDrawable) {
                (drawable as? GLOffscreenAutoDrawable)?.setSurfaceSize(window.width, window.height)
            }

            logger.debug("queryResize: $lastWidth/$lastHeight")

            lastResize = -1L
        }
    }

    /**
     * OpenGL Buffer class, creates a buffer associated with the context [gl] and size [size] in bytes.
     *
     * @author Ulrik Guenther <hello@ulrik.is>
     */
    class OpenGLBuffer(var gl: GL4, var size: Int) {
        /** Temporary buffer for data before it is sent to the GPU. */
        var buffer: ByteBuffer
            private set
        /** OpenGL id of the buffer. */
        var id = intArrayOf(-1)
            private set
        /** Required buffer offset alignment for uniform buffers, determined from [GL4.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT] */
        var alignment = 256L
            private set

        init {
            val tmp = intArrayOf(0, 0)
            gl.glGetIntegerv(GL4.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, tmp, 0)
            alignment = tmp[0].toLong()

            gl.glGenBuffers(1, id, 0)
            buffer = MemoryUtil.memAlloc(maxOf(tmp[0], size))

            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, id[0])
            gl.glBufferData(GL4.GL_UNIFORM_BUFFER, size * 1L, null, GL4.GL_DYNAMIC_DRAW)
            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0)
        }

        /** Copies the [buffer] from main memory to GPU memory. */
        fun copyFromStagingBuffer() {
            buffer.flip()

            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, id[0])
            gl.glBufferSubData(GL4.GL_UNIFORM_BUFFER, 0, buffer.remaining() * 1L, buffer)
            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0)
        }

        /** Resets staging buffer position and limit */
        fun reset() {
            buffer.position(0)
            buffer.limit(size)
        }

        /**
         * Resizes the backing buffer to [newSize], which is 1.5x the original size by default,
         * and returns the staging buffer.
         */
        fun resize(newSize: Int = (buffer.capacity() * 1.5f).roundToInt()): ByteBuffer {
            logger.debug("Resizing backing buffer of $this from ${buffer.capacity()} to $newSize")

            // resize main memory-backed buffer
            buffer = MemoryUtil.memRealloc(buffer, newSize) ?: throw IllegalStateException("Could not resize buffer")
            size = buffer.capacity()

            // resize OpenGL buffer as well
            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, id[0])
            gl.glBufferData(GL4.GL_UNIFORM_BUFFER, size * 1L, null, GL4.GL_DYNAMIC_DRAW)
            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0)

            return buffer
        }

        /**
         * Returns the [buffer]'s remaining bytes.
         */
        fun remaining() = buffer.remaining()

        /**
         * Advances the backing buffer for population, aligning it by [alignment], or any given value
         * that overrides it (not recommended), returns the buffers new position.
         */
        fun advance(align: Long = this.alignment): Int {
            val pos = buffer.position()
            val rem = pos.rem(align)

            if (rem != 0L) {
                val newpos = pos + align.toInt() - rem.toInt()
                buffer.position(newpos)
            }

            return buffer.position()
        }
    }

    data class DefaultBuffers(var UBOs: OpenGLBuffer,
                              var LightParameters: OpenGLBuffer,
                              var ShaderParameters: OpenGLBuffer,
                              var VRParameters: OpenGLBuffer,
                              var ShaderProperties: OpenGLBuffer)

    protected lateinit var buffers: DefaultBuffers
    protected val sceneUBOs = CopyOnWriteArrayList<Node>()

    protected val resizeHandler = OpenGLResizeHandler()

    companion object {
        private const val WINDOW_RESIZE_TIMEOUT = 200L
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
    }

    /**
     * Constructor for OpenGLRenderer, initialises geometry buffers
     * according to eye configuration. Also initialises different rendering passes.
     *
     */
    init {

        logger.info("Initializing OpenGL Renderer...")
        this.hub = hub
        this.settings = loadDefaultRendererSettings(hub.get(SceneryElement.Settings) as Settings)
        this.window.width = width
        this.window.height = height

        this.renderConfigFile = renderConfigFile
        this.renderConfig = RenderConfigReader().loadFromFile(renderConfigFile)

        this.flow = this.renderConfig.createRenderpassFlow()

        logger.info("Loaded ${renderConfig.name} (${renderConfig.description ?: "no description"})")

        this.scene = scene
        this.applicationName = applicationName

        val hmd = hub.getWorkingHMDDisplay()
        if (settings.get("vr.Active") && hmd != null) {
            this.window.width = hmd.getRenderTargetSize().x() * 2
            this.window.height = hmd.getRenderTargetSize().y()
        }

        if (embedIn != null || embedInDrawable != null) {
            if (embedIn != null && embedInDrawable == null) {
                val profile = GLProfile.getMaxProgrammableCore(true)

                if (!profile.isGL4) {
                    throw UnsupportedOperationException("Could not create OpenGL 4 context, perhaps you need a graphics driver update?")
                }

                val caps = GLCapabilities(profile)
                caps.hardwareAccelerated = true
                caps.doubleBuffered = true
                caps.isOnscreen = false
                caps.numSamples = 1
                caps.isPBuffer = true
                caps.redBits = 8
                caps.greenBits = 8
                caps.blueBits = 8
                caps.alphaBits = 8

                val panel = embedIn
                /* maybe better?
                 var canvas: ClearGLWindow? = null
                    SwingUtilities.invokeAndWait {
                        canvas = ClearGLWindow("", width, height, this)
                        canvas!!.newtCanvasAWT.shallUseOffscreenLayer = true
                        panel.component = canvas!!.newtCanvasAWT
                        panel.layout = BorderLayout()
                        panel.add(canvas!!.newtCanvasAWT, BorderLayout.CENTER)
                        panel.preferredSize = Dimension(width, height)

                        val frame = SwingUtilities.getAncestorOfClass(JFrame::class.java, panel) as JFrame
                        frame.preferredSize = Dimension(width, height)
                        frame.layout = BorderLayout()
                        frame.pack()
                        frame.isVisible = true
                    }

                    canvas!!.glAutoDrawable
                 */
                drawable = if (panel is SceneryJPanel) {
                    val surfaceScale = hub.get<Settings>()?.get("Renderer.SurfaceScale", Vector2f(1.0f, 1.0f)) ?: Vector2f(1.0f, 1.0f)
                    this.window.width = (panel.width * surfaceScale.x()).toInt()
                    this.window.height = (panel.height * surfaceScale.y()).toInt()

                    panel.panelWidth = this.window.width
                    panel.panelHeight = this.window.height

                    logger.debug("Surface scale: $surfaceScale")
                    val canvas = ClearGLWindow("",
                        this.window.width,
                        this.window.height, null)
                    canvas.newtCanvasAWT.shallUseOffscreenLayer = true
                    panel.component = canvas.newtCanvasAWT
                    panel.cglWindow = canvas
                    panel.layout = BorderLayout()
                    panel.preferredSize = Dimension(
                        (window.width * surfaceScale.x()).toInt(),
                        (window.height * surfaceScale.y()).toInt())
                    canvas.newtCanvasAWT.preferredSize = panel.preferredSize

                    panel.border = BorderFactory.createEmptyBorder()
                    panel.add(canvas.newtCanvasAWT, BorderLayout.CENTER)


                    cglWindow = canvas
                    canvas.glAutoDrawable
                } else {
                    val factory = GLDrawableFactory.getFactory(profile)

                    factory.createOffscreenAutoDrawable(factory.defaultDevice, caps,
                        DefaultGLCapabilitiesChooser(), window.width, window.height)
                }
            } else {
                drawable = embedInDrawable
            }

            drawable?.apply {

                addGLEventListener(this@OpenGLRenderer)

                animator = Animator()
                animator.add(this)
                animator.start()

                embedInDrawable?.let { glAutoDrawable ->
                    window = SceneryWindow.JOGLDrawable(glAutoDrawable)
                }

                window.width = width
                window.height = height

                resizeHandler.lastWidth = window.width
                resizeHandler.lastHeight = window.height

                embedIn?.let { panel ->
                    panel.imageScaleY = -1.0f
                    window = panel.init(resizeHandler)
                    val surfaceScale = hub.get<Settings>()?.get("Renderer.SurfaceScale", Vector2f(1.0f, 1.0f)) ?: Vector2f(1.0f, 1.0f)

                    window.width = (panel.panelWidth * surfaceScale.x()).toInt()
                    window.height = (panel.panelHeight * surfaceScale.y()).toInt()
                }

                resizeHandler.lastWidth = window.width
                resizeHandler.lastHeight = window.height
            }
        } else {
            val w = this.window.width
            val h = this.window.height

            // need to leak this here unfortunately
            @Suppress("LeakingThis")
            cglWindow = ClearGLWindow("",
                w,
                h, this,
                false).apply {

                if(embedIn == null) {
                    window = SceneryWindow.ClearGLWindow(this)
                    window.width = w
                    window.height = h

                    val windowAdapter = object: WindowAdapter() {
                        override fun windowDestroyNotify(e: WindowEvent?) {
                            logger.debug("Signalling close from window event")
                            e?.isConsumed = true
                        }
                    }

                    this.addWindowListener(windowAdapter)

                    this.setFPS(60)
                    this.start()
                    this.setDefaultCloseOperation(WindowClosingProtocol.WindowClosingMode.DISPOSE_ON_CLOSE)

                    this.isVisible = true
                }
            }
        }

        while(!initialized) {
            Thread.sleep(20)
        }
    }

    override fun init(pDrawable: GLAutoDrawable) {
        this.gl = pDrawable.gl.gL4

        val width = this.window.width
        val height = this.window.height

        gl.swapInterval = 0

        val driverString = gl.glGetString(GL4.GL_RENDERER)
        val driverVersion = gl.glGetString(GL4.GL_VERSION)
        logger.info("OpenGLRenderer: $width x $height on $driverString, $driverVersion")

        if (driverVersion.toLowerCase().indexOf("nvidia") != -1 && System.getProperty("os.name").toLowerCase().indexOf("windows") != -1) {
            gpuStats = NvidiaGPUStats()
        }

        val tmp = IntArray(1)
        gl.glGetIntegerv(GL4.GL_MAX_TEXTURE_IMAGE_UNITS, tmp, 0)
        maxTextureUnits = tmp[0]

        val numExtensionsBuffer = IntBuffer.allocate(1)
        gl.glGetIntegerv(GL4.GL_NUM_EXTENSIONS, numExtensionsBuffer)
        val extensions = (0 until numExtensionsBuffer[0]).map { gl.glGetStringi(GL4.GL_EXTENSIONS, it) }
        logger.debug("Available OpenGL extensions: ${extensions.joinToString(", ")}")

        settings.set("ssao.FilterRadius", Vector2f(5.0f / width, 5.0f / height))

        buffers = DefaultBuffers(
            UBOs = OpenGLBuffer(gl, 10 * 1024 * 1024),
            LightParameters = OpenGLBuffer(gl, 10 * 1024 * 1024),
            VRParameters = OpenGLBuffer(gl, 2 * 1024),
            ShaderProperties = OpenGLBuffer(gl, 10 * 1024 * 1024),
            ShaderParameters = OpenGLBuffer(gl, 128 * 1024))

        prepareDefaultTextures()

        renderpasses = prepareRenderpasses(renderConfig, window.width, window.height)

        // enable required features
//        gl.glEnable(GL4.GL_TEXTURE_GATHER)
        gl.glEnable(GL4.GL_PROGRAM_POINT_SIZE)

        heartbeatTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                fps = framesPerSec
                framesPerSec = 0

                if(!pushMode) {
                    (hub?.get(SceneryElement.Statistics) as? Statistics)?.add("Renderer.fps", fps, false)
                }

                gpuStats?.let {
                    it.update(0)

                    hub?.get(SceneryElement.Statistics).let { s ->
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
            }
        }, 0, 1000)

        initialized = true
    }

    private fun Node.rendererMetadata(): OpenGLObjectState? {
        return this.metadata["OpenGLRenderer"] as? OpenGLObjectState
    }

    private fun getSupersamplingFactor(window: ClearGLWindow?): Float {
        val supersamplingFactor = if(settings.get<Float>("Renderer.SupersamplingFactor").toInt() == 1) {
            if(window != null && ClearGLWindow.isRetina(window.gl)) {
                logger.debug("Setting Renderer.SupersamplingFactor to 0.5, as we are rendering on a retina display.")
                settings.set("Renderer.SupersamplingFactor", 0.5f)
                0.5f
            } else {
                settings.get("Renderer.SupersamplingFactor")
            }
        } else {
            settings.get("Renderer.SupersamplingFactor")
        }

        return supersamplingFactor
    }

    fun prepareRenderpasses(config: RenderConfigReader.RenderConfig, windowWidth: Int, windowHeight: Int): LinkedHashMap<String, OpenGLRenderpass> {
        if(config.sRGB) {
            gl.glEnable(GL4.GL_FRAMEBUFFER_SRGB)
        } else {
            gl.glDisable(GL4.GL_FRAMEBUFFER_SRGB)
        }

        buffers.ShaderParameters.reset()

        val framebuffers = ConcurrentHashMap<String, GLFramebuffer>()
        val passes = LinkedHashMap<String, OpenGLRenderpass>()

        val flow = renderConfig.createRenderpassFlow()

        val supersamplingFactor = getSupersamplingFactor(cglWindow)

        scene.findObserver()?.let { cam ->
            when(cam.projectionType) {
                Camera.ProjectionType.Perspective -> cam.perspectiveCamera(
                    cam.fov,
                    (windowWidth * supersamplingFactor).roundToInt(),
                    (windowHeight * supersamplingFactor).roundToInt(),
                    cam.nearPlaneDistance,
                    cam.farPlaneDistance
                )

                Camera.ProjectionType.Orthographic -> cam.orthographicCamera(
                    cam.fov,
                    (windowWidth * supersamplingFactor).roundToInt(),
                    (windowHeight * supersamplingFactor).roundToInt(),
                    cam.nearPlaneDistance,
                    cam.farPlaneDistance
                )

                Camera.ProjectionType.Undefined -> {
                    logger.warn("Camera ${cam.name} has undefined projection type, using default perspective projection")
                    cam.perspectiveCamera(
                        cam.fov,
                        (windowWidth * supersamplingFactor).roundToInt(),
                        (windowHeight * supersamplingFactor).roundToInt(),
                        cam.nearPlaneDistance,
                        cam.farPlaneDistance
                    )
                }
            }
        }

        settings.set("Renderer.displayWidth", (windowWidth * supersamplingFactor).toInt())
        settings.set("Renderer.displayHeight", (windowHeight * supersamplingFactor).toInt())

        flow.map { passName ->
            val passConfig = config.renderpasses.getValue(passName)
            val pass = OpenGLRenderpass(passName, passConfig)

            var width = windowWidth
            var height = windowHeight

            config.rendertargets.filter { it.key == passConfig.output.name }.map { rt ->
                width = (supersamplingFactor * windowWidth * rt.value.size.first).toInt()
                height = (supersamplingFactor * windowHeight * rt.value.size.second).toInt()
                logger.info("Creating render framebuffer ${rt.key} for pass $passName (${width}x$height)")

                settings.set("Renderer.$passName.displayWidth", width)
                settings.set("Renderer.$passName.displayHeight", height)

                if (framebuffers.containsKey(rt.key)) {
                    logger.info("Reusing already created framebuffer")
                    pass.output.put(rt.key, framebuffers.getValue(rt.key))
                } else {
                    val framebuffer = GLFramebuffer(gl, width, height, renderConfig.sRGB)

                    rt.value.attachments.forEach { att ->
                        logger.info(" + attachment ${att.key}, ${att.value.name}")

                        when (att.value) {
                            RenderConfigReader.TargetFormat.RGBA_Float32 -> framebuffer.addFloatRGBABuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.RGBA_Float16 -> framebuffer.addFloatRGBABuffer(gl, att.key, 16)

                            RenderConfigReader.TargetFormat.RGB_Float32 -> framebuffer.addFloatRGBBuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.RGB_Float16 -> framebuffer.addFloatRGBBuffer(gl, att.key, 16)

                            RenderConfigReader.TargetFormat.RG_Float32 -> framebuffer.addFloatRGBuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.RG_Float16 -> framebuffer.addFloatRGBuffer(gl, att.key, 16)
                            RenderConfigReader.TargetFormat.R_Float16 -> framebuffer.addFloatRBuffer(gl, att.key, 16)

                            RenderConfigReader.TargetFormat.RGBA_UInt16 -> framebuffer.addUnsignedByteRGBABuffer(gl, att.key, 16)
                            RenderConfigReader.TargetFormat.RGBA_UInt8 -> framebuffer.addUnsignedByteRGBABuffer(gl, att.key, 8)
                            RenderConfigReader.TargetFormat.R_UInt16 -> framebuffer.addUnsignedByteRBuffer(gl, att.key, 16)
                            RenderConfigReader.TargetFormat.R_UInt8 -> framebuffer.addUnsignedByteRBuffer(gl, att.key, 8)

                            RenderConfigReader.TargetFormat.Depth32 -> framebuffer.addDepthBuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.Depth24 -> framebuffer.addDepthBuffer(gl, att.key, 24)
                        }
                    }

                    pass.output[rt.key] = framebuffer
                    framebuffers.put(rt.key, framebuffer)
                }
            }

            if(passConfig.output.name == "Viewport") {
                width = (supersamplingFactor * windowWidth).toInt()
                height = (supersamplingFactor * windowHeight).toInt()
                logger.info("Creating render framebuffer Viewport for pass $passName (${width}x$height)")

                settings.set("Renderer.$passName.displayWidth", width)
                settings.set("Renderer.$passName.displayHeight", height)

                val framebuffer = GLFramebuffer(gl, width, height)
                framebuffer.addUnsignedByteRGBABuffer(gl, "Viewport", 8)

                pass.output["Viewport"] = framebuffer
                framebuffers["Viewport"] = framebuffer
            }

            pass.openglMetadata.renderArea = OpenGLRenderpass.Rect2D(
                (pass.passConfig.viewportSize.first * width).toInt(),
                (pass.passConfig.viewportSize.second * height).toInt(),
                (pass.passConfig.viewportOffset.first * width).toInt(),
                (pass.passConfig.viewportOffset.second * height).toInt())
            logger.debug("Render area for $passName: ${pass.openglMetadata.renderArea.width}x${pass.openglMetadata.renderArea.height}")

            pass.openglMetadata.viewport = OpenGLRenderpass.Viewport(OpenGLRenderpass.Rect2D(
                (pass.passConfig.viewportSize.first * width).toInt(),
                (pass.passConfig.viewportSize.second * height).toInt(),
                (pass.passConfig.viewportOffset.first * width).toInt(),
                (pass.passConfig.viewportOffset.second * height).toInt()),
                0.0f, 1.0f)

            pass.openglMetadata.scissor = OpenGLRenderpass.Rect2D(
                (pass.passConfig.viewportSize.first * width).toInt(),
                (pass.passConfig.viewportSize.second * height).toInt(),
                (pass.passConfig.viewportOffset.first * width).toInt(),
                (pass.passConfig.viewportOffset.second * height).toInt())

            pass.openglMetadata.eye = pass.passConfig.eye
            pass.defaultShader = prepareShaderProgram(
                Shaders.ShadersFromFiles(pass.passConfig.shaders.map { "shaders/$it" }.toTypedArray()))

            pass.initializeShaderParameters(settings, buffers.ShaderParameters)

            passes.put(passName, pass)
        }

        // connect inputs
        passes.forEach { pass ->
            val passConfig = config.renderpasses.getValue(pass.key)

            passConfig.inputs?.forEach { inputTarget ->
                val targetName = if(inputTarget.name.contains(".")) {
                    inputTarget.name.substringBefore(".")
                } else {
                    inputTarget.name
                }

                passes.filter {
                    it.value.output.keys.contains(targetName)
                }.forEach {
                    val output = it.value.output[targetName] ?: throw IllegalStateException("Output for $targetName not found in configuration")
                    pass.value.inputs[inputTarget.name] = output
                }
            }

            with(pass.value) {
                // initialize pass if needed
            }
        }

        return passes
    }

    protected fun prepareShaderProgram(shaders: Shaders): OpenGLShaderProgram? {

        val modules = HashMap<ShaderType, OpenGLShaderModule>()

        ShaderType.values().forEach { type ->
            try {
                val m = OpenGLShaderModule.getFromCacheOrCreate(gl, "main", shaders.get(Shaders.ShaderTarget.OpenGL, type))
                modules[m.shaderType] = m
            } catch (e: ShaderNotFoundException) {
                if(shaders is Shaders.ShadersFromFiles) {
                    logger.debug("Could not locate shader for $shaders, type=$type, ${shaders.shaders.joinToString(",")} - this is normal if there are no errors reported")
                } else {
                    logger.debug("Could not locate shader for $shaders, type=$type - this is normal if there are no errors reported")
                }
            }
        }

        val program = OpenGLShaderProgram(gl, modules)
        return if(program.isValid()) {
            program
        } else {
            null
        }
    }

    override fun display(pDrawable: GLAutoDrawable) {
        val fps = pDrawable.animator?.lastFPS ?: 0.0f

        if(embedIn == null) {
            window.title = "$applicationName [${this@OpenGLRenderer.className}] - ${fps.toInt()} fps"
        }

        this.joglDrawable = pDrawable

        if (mustRecreateFramebuffers) {
            logger.info("Recreating framebuffers (${window.width}x${window.height})")

            // FIXME: This needs to be done here in order to be able to run on HiDPI screens correctly
            // FIXME: On macOS, this _must_ not be called, otherwise JOGL bails out, on Windows, it needs to be called.
            if(embedIn != null && Platform.get() != Platform.MACOSX) {
                cglWindow?.newtCanvasAWT?.setBounds(0, 0, window.width, window.height)
            }

            renderpasses = prepareRenderpasses(renderConfig, window.width, window.height)
            flow = renderConfig.createRenderpassFlow()

            framebufferRecreateHook.invoke()

            frames = 0
            mustRecreateFramebuffers = false
        }

        this@OpenGLRenderer.renderInternal()
    }

    override fun setClearGLWindow(pClearGLWindow: ClearGLWindow) {
        cglWindow = pClearGLWindow
    }

    override fun getClearGLWindow(): ClearGLDisplayable {
        return cglWindow ?: throw IllegalStateException("No ClearGL window available")
    }

    override fun reshape(pDrawable: GLAutoDrawable,
                         pX: Int,
                         pY: Int,
                         pWidth: Int,
                         pHeight: Int) {
        var height = pHeight

        if (height == 0)
            height = 1

        this@OpenGLRenderer.reshape(pWidth, height)
    }

    override fun dispose(pDrawable: GLAutoDrawable) {
        initialized = false
        try {

            scene.discover(scene, { _ -> true }).forEach {
                destroyNode(it)
            }

            scene.initialized = false

            if(cglWindow != null) {
                logger.debug("Closing window")
                joglDrawable?.animator?.stop()
            } else {
                logger.debug("Closing drawable")
                joglDrawable?.animator?.stop()
            }

        } catch(e: ThreadDeath) {
            logger.debug("Caught JOGL ThreadDeath, ignoring.")
        }
    }

    /**
     * Based on the [GLFramebuffer], devises a texture unit that can be used
     * for object textures.
     *
     * @param[type] texture type
     * @return Int of the texture unit to be used
     */
    fun textureTypeToUnit(target: OpenGLRenderpass, type: String): Int {
        val offset = if (target.inputs.values.isNotEmpty()) {
            target.inputs.values.sumBy { it.boundBufferNum }
        } else {
            0
        }

        return offset + when (type) {
            "ambient" -> 0
            "diffuse" -> 1
            "specular" -> 2
            "normal" -> 3
            "alphamask" -> 4
            "displacement" -> 5
            "3D-volume" -> 6
            else -> {
                logger.warn("Unknown ObjecTextures type $type")
                0
            }
        }
    }

    private fun textureTypeToArrayName(type: String): String {
        return when (type) {
            "ambient" -> "ObjectTextures[0]"
            "diffuse" -> "ObjectTextures[1]"
            "specular" -> "ObjectTextures[2]"
            "normal" -> "ObjectTextures[3]"
            "alphamask" -> "ObjectTextures[4]"
            "displacement" -> "ObjectTextures[5]"
            "3D-volume" -> "VolumeTextures"
            else -> {
                logger.debug("Unknown texture type $type")
                type
            }
        }
    }

    /**
     * Converts a [GeometryType] to an OpenGL geometry type
     *
     * @return Int of the OpenGL geometry type.
     */
    private fun GeometryType.toOpenGLType(): Int {
        return when (this) {
            GeometryType.TRIANGLE_STRIP -> GL4.GL_TRIANGLE_STRIP
            GeometryType.POLYGON -> GL4.GL_TRIANGLES
            GeometryType.TRIANGLES -> GL4.GL_TRIANGLES
            GeometryType.TRIANGLE_FAN -> GL4.GL_TRIANGLE_FAN
            GeometryType.POINTS -> GL4.GL_POINTS
            GeometryType.LINE -> GL4.GL_LINE_STRIP
            GeometryType.LINES_ADJACENCY -> GL4.GL_LINES_ADJACENCY
            GeometryType.LINE_STRIP_ADJACENCY -> GL4.GL_LINE_STRIP_ADJACENCY
        }
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

    /**
     * Toggles deferred shading buffer debug view. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand]
     */
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
     * Toggles Screen-space ambient occlusion. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun toggleSSAO() {
        if (!settings.get<Boolean>("ssao.Active")) {
            settings.set("ssao.Active", true)
        } else {
            settings.set("ssao.Active", false)
        }
    }

    /**
     * Toggles HDR rendering. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun toggleHDR() {
        if (!settings.get<Boolean>("hdr.Active")) {
            settings.set("hdr.Active", true)
        } else {
            settings.set("hdr.Active", false)
        }
    }

    /**
     * Increases the HDR exposure value. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun increaseExposure() {
        val exp: Float = settings.get("hdr.Exposure")
        settings.set("hdr.Exposure", exp + 0.05f)
    }

    /**
     * Decreases the HDR exposure value.Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun decreaseExposure() {
        val exp: Float = settings.get("hdr.Exposure")
        settings.set("hdr.Exposure", exp - 0.05f)
    }

    /**
     * Increases the HDR gamma value. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("unused")
    fun increaseGamma() {
        val gamma: Float = settings.get("hdr.Gamma")
        settings.set("hdr.Gamma", gamma + 0.05f)
    }

    /**
     * Decreases the HDR gamma value. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("unused")
    fun decreaseGamma() {
        val gamma: Float = settings.get("hdr.Gamma")
        if (gamma - 0.05f >= 0) settings.set("hdr.Gamma", gamma - 0.05f)
    }

    /**
     * Toggles fullscreen. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("unused")
    fun toggleFullscreen() {
        if (!settings.get<Boolean>("wantsFullscreen")) {
            settings.set("wantsFullscreen", true)
        } else {
            settings.set("wantsFullscreen", false)
        }
    }

    /**
     * Convenience function that extracts the [OpenGLObjectState] from a [Node]'s
     * metadata.
     *
     * @param[node] The node of interest
     * @return The [OpenGLObjectState] of the [Node]
     */
    fun getOpenGLObjectStateFromNode(node: Node): OpenGLObjectState {
        return node.metadata["OpenGLRenderer"] as OpenGLObjectState
    }

    /**
     * Initializes the [Scene] with the [OpenGLRenderer], to be called
     * before [render].
     */
    @Synchronized override fun initializeScene() {
        scene.discover(scene, { it is HasGeometry })
            .forEach { it ->
                it.metadata["OpenGLRenderer"] = OpenGLObjectState()
                initializeNode(it)
            }

        scene.initialized = true
        logger.info("Initialized ${textureCache.size} textures")
    }

    @Suppress("UNUSED_VALUE")
    @Synchronized protected fun updateDefaultUBOs(cam: Camera): Boolean {
        // sticky boolean
        var updated: Boolean by StickyBoolean(initial = false)

        val hmd = hub?.getWorkingHMDDisplay()?.wantsVR(settings)

        cam.view = cam.getTransformation()

        buffers.VRParameters.reset()
        val vrUbo = uboCache.computeIfAbsent("VRParameters") {
            OpenGLUBO(backingBuffer = buffers.VRParameters)
        }

        vrUbo.add("projection0", {
            (hmd?.getEyeProjection(0, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection)
        })
        vrUbo.add("projection1", {
            (hmd?.getEyeProjection(1, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection)
        })
        vrUbo.add("inverseProjection0", {
            Matrix4f(hmd?.getEyeProjection(0, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection).invert()
        })
        vrUbo.add("inverseProjection1", {
            Matrix4f(hmd?.getEyeProjection(1, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection).invert()
        })
        vrUbo.add("headShift", { hmd?.getHeadToEyeTransform(0) ?: Matrix4f().identity() })
        vrUbo.add("IPD", { hmd?.getIPD() ?: 0.05f })
        vrUbo.add("stereoEnabled", { renderConfig.stereoEnabled.toInt() })

        updated = vrUbo.populate()
        buffers.VRParameters.copyFromStagingBuffer()

        buffers.UBOs.reset()
        buffers.ShaderProperties.reset()

        sceneUBOs.forEach { node ->
            var nodeUpdated: Boolean by StickyBoolean(initial = false)
            if (!node.metadata.containsKey(className)) {
                return@forEach
            }

            val s = node.metadata[className] as? OpenGLObjectState
            if(s == null) {
                logger.warn("Could not get OpenGLObjectState for ${node.name}")
                return@forEach
            }

            val ubo = s.UBOs["Matrices"]
            if(ubo?.backingBuffer == null) {
                logger.warn("Matrices UBO for ${node.name} does not exist or does not have a backing buffer")
                return@forEach
            }

            preDrawAndUpdateGeometryForNode(node)

            var bufferOffset = ubo.advanceBackingBuffer()
            ubo.offset = bufferOffset
            node.view.set(cam.view)
            nodeUpdated = ubo.populate(offset = bufferOffset.toLong())

            val materialUbo = (node.metadata["OpenGLRenderer"]!! as OpenGLObjectState).UBOs.getValue("MaterialProperties")
            bufferOffset = ubo.advanceBackingBuffer()
            materialUbo.offset = bufferOffset

            nodeUpdated = materialUbo.populate(offset = bufferOffset.toLong())

            if (s.UBOs.containsKey("ShaderProperties")) {
                val propertyUbo = s.UBOs.getValue("ShaderProperties")
                // TODO: Correct buffer advancement
                val offset = propertyUbo.backingBuffer!!.advance()
                updated = propertyUbo.populate(offset = offset.toLong())
                propertyUbo.offset = offset
            }

            nodeUpdated = loadTexturesForNode(node, s)

            nodeUpdated = if(node.material.materialHashCode() != s.materialHash) {
                s.initialized = false
                initializeNode(node)
                true
            } else {
                false
            }

            if(nodeUpdated && node.getScene()?.onNodePropertiesChanged?.isNotEmpty() == true) {
                GlobalScope.launch { node.getScene()?.onNodePropertiesChanged?.forEach { it.value.invoke(node) } }
            }

            updated = nodeUpdated
        }

        buffers.UBOs.copyFromStagingBuffer()
        buffers.LightParameters.reset()

//        val lights = sceneObjects.filter { it is PointLight }

        val lightUbo = uboCache.computeIfAbsent("LightParameters") {
            OpenGLUBO(backingBuffer = buffers.LightParameters)
        }

        lightUbo.add("ViewMatrix0", { cam.getTransformationForEye(0) })
        lightUbo.add("ViewMatrix1", { cam.getTransformationForEye(1) })
        lightUbo.add("InverseViewMatrix0", { cam.getTransformationForEye(0).invert() })
        lightUbo.add("InverseViewMatrix1", { cam.getTransformationForEye(1).invert() })
        lightUbo.add("ProjectionMatrix", { cam.projection })
        lightUbo.add("InverseProjectionMatrix", { Matrix4f(cam.projection).invert() })
        lightUbo.add("CamPosition", { cam.position })
//        lightUbo.add("numLights", { lights.size })

//        lights.forEachIndexed { i, light ->
//            val l = light as PointLight
//            l.updateWorld(true, false)
//
//            lightUbo.add("Linear-$i", { l.linear })
//            lightUbo.add("Quadratic-$i", { l.quadratic })
//            lightUbo.add("Intensity-$i", { l.intensity })
//            lightUbo.add("Radius-$i", { -l.linear + Math.sqrt(l.linear * l.linear - 4 * l.quadratic * (1.0 - (256.0f / 5.0) * 100)).toFloat() })
//            lightUbo.add("Position-$i", { l.position })
//            lightUbo.add("Color-$i", { l.emissionColor })
//            lightUbo.add("filler-$i", { 0.0f })
//        }

        updated = lightUbo.populate()

        buffers.ShaderParameters.reset()
        renderpasses.forEach { name, pass ->
            logger.trace("Updating shader parameters for {}", name)
            updated = pass.updateShaderParameters()
        }
        buffers.ShaderParameters.copyFromStagingBuffer()

        buffers.LightParameters.copyFromStagingBuffer()
        buffers.ShaderProperties.copyFromStagingBuffer()

        return updated
    }


    /**
     * Update a [Node]'s geometry, if needed and run it's preDraw() routine.
     *
     * @param[n] The Node to update and preDraw()
     */
    private fun preDrawAndUpdateGeometryForNode(n: Node) {
        if (n is HasGeometry) {
            if (n.dirty) {
                n.preUpdate(this, hub)
                if (n.lock.tryLock()) {
                    if (n.vertices.remaining() > 0 && n.normals.remaining() > 0) {
                        updateVertices(n)
                        updateNormals(n)
                    }

                    if (n.texcoords.remaining() > 0) {
                        updateTextureCoords(n)
                    }

                    if (n.indices.remaining() > 0) {
                        updateIndices(n)
                    }

                    n.dirty = false

                    n.lock.unlock()
                }
            }

            n.preDraw()
        }
    }

    /**
     * Set a [GLProgram]'s uniforms according to a [Node]'s [ShaderProperty]s.
     *
     * This functions uses reflection to query for a Node's declared fields and checks
     * whether they are marked up with the [ShaderProperty] annotation. If this is the case,
     * the [GLProgram]'s uniform with the same name as the field is set to its value.
     *
     * Currently limited to Vector3f, Matrix4f, Int and Float properties.
     *
     * @param[n] The Node to search for [ShaderProperty]s
     * @param[program] The [GLProgram] used to render the Node
     */
    @Suppress("unused")
    private fun setShaderPropertiesForNode(n: Node, program: GLProgram) {
        shaderPropertyCache
            .getOrPut(n.javaClass) { n.javaClass.declaredFields.filter { it.isAnnotationPresent(ShaderProperty::class.java) } }
            .forEach { property ->
                property.isAccessible = true
                val field = property.get(n)

                when (property.type) {
                    Vector2f::class.java -> {
                        val v = field as Vector2f
                        program.getUniform(property.name).setFloatVector2(v.x, v.y)
                    }

                    Vector3f::class.java -> {
                        val v = field as Vector3f
                        program.getUniform(property.name).setFloatVector3(v.x, v.y, v.z)
                    }

                    Vector4f::class.java -> {
                        val v = field as Vector4f
                        program.getUniform(property.name).setFloatVector3(v.x, v.y, v.z, v.w)
                    }

                    Int::class.java -> {
                        program.getUniform(property.name).setInt(field as Int)
                    }

                    Float::class.java -> {
                        program.getUniform(property.name).setFloat(field as Float)
                    }

                    Matrix4f::class.java -> {
                        val m = field as Matrix4f
                        val array = FloatArray(16)
                        m.get(array)

                        program.getUniform(property.name).setFloatMatrix(array, false)
                    }

                    else -> {
                        logger.warn("Could not derive shader data type for @ShaderProperty ${n.javaClass.canonicalName}.${property.name} of type ${property.type}!")
                    }
                }
            }
    }

    private fun blitFramebuffers(source: GLFramebuffer?, target: GLFramebuffer?,
                                 sourceOffset: OpenGLRenderpass.Rect2D,
                                 targetOffset: OpenGLRenderpass.Rect2D,
                                 colorOnly: Boolean = false, depthOnly: Boolean = false,
                                 sourceName: String? = null) {
        if (target != null) {
            target.setDrawBuffers(gl)
        } else {
            gl.glBindFramebuffer(GL4.GL_DRAW_FRAMEBUFFER, 0)
        }

        if (source != null) {
            if(sourceName != null) {
                source.setReadBuffers(gl, sourceName)
            } else {
                source.setReadBuffers(gl)
            }
        } else {
            gl.glBindFramebuffer(GL4.GL_READ_FRAMEBUFFER, 0)
        }

        val (blitColor, blitDepth) = when {
            colorOnly && !depthOnly -> true to false
            !colorOnly && depthOnly -> false to true
            else -> true to true
        }

        if(blitColor) {
            if (source?.hasColorAttachment() != false) {
                gl.glBlitFramebuffer(
                    sourceOffset.offsetX, sourceOffset.offsetY,
                    sourceOffset.offsetX + sourceOffset.width, sourceOffset.offsetY + sourceOffset.height,
                    targetOffset.offsetX, targetOffset.offsetY,
                    targetOffset.offsetX + targetOffset.width, targetOffset.offsetY + targetOffset.height,
                    GL4.GL_COLOR_BUFFER_BIT, GL4.GL_LINEAR)
            }
        }

        if(blitDepth) {
            if ((source?.hasDepthAttachment() != false && target?.hasDepthAttachment() != false) || (depthOnly && !colorOnly)) {
                gl.glBlitFramebuffer(
                    sourceOffset.offsetX, sourceOffset.offsetY,
                    sourceOffset.offsetX + sourceOffset.width, sourceOffset.offsetY + sourceOffset.height,
                    targetOffset.offsetX, targetOffset.offsetY,
                    targetOffset.offsetX + targetOffset.width, targetOffset.offsetY + targetOffset.height,
                    GL4.GL_DEPTH_BUFFER_BIT, GL4.GL_NEAREST)
            } else {
                logger.trace("Either source or target don't have a depth buffer. If blitting to window surface, this is not a problem.")
            }
        }

        gl.glBindFramebuffer(GL4.GL_FRAMEBUFFER, 0)
    }

    private fun updateInstanceBuffers(sceneObjects:List<Node>): Boolean {
        val instanceMasters = sceneObjects.filter { it.instances.size > 0 }

        instanceMasters.forEach { parent ->
            var metadata = parent.rendererMetadata()

            if(metadata == null) {
                parent.metadata["OpenGLRenderer"] = OpenGLObjectState()
                initializeNode(parent)
                metadata = parent.rendererMetadata()
            }

            updateInstanceBuffer(parent, metadata)
        }

        return instanceMasters.isNotEmpty()
    }

    private fun updateInstanceBuffer(parentNode: Node, state: OpenGLObjectState?): OpenGLObjectState {
        if(state == null) {
            throw IllegalStateException("Metadata for ${parentNode.name} is null at updateInstanceBuffer(${parentNode.name}). This is a bug.")
        }

        // parentNode.instances is a CopyOnWrite array list, and here we keep a reference to the original.
        // If it changes in the meantime, no problemo.
        val instances = parentNode.instances
        logger.trace("Updating instance buffer for ${parentNode.name}")

        if (instances.isEmpty()) {
            logger.debug("$parentNode has no child instances attached, returning.")
            return state
        }

        // first we create a fake UBO to gauge the size of the needed properties
        val ubo = OpenGLUBO()
        ubo.fromInstance(instances.first())

        val instanceBufferSize = ubo.getSize() * instances.size

        val existingStagingBuffer = state.vertexBuffers["instanceStaging"]
        val stagingBuffer = if(existingStagingBuffer != null
            && existingStagingBuffer.capacity() >= instanceBufferSize
            && existingStagingBuffer.capacity() < 1.5*instanceBufferSize) {
            existingStagingBuffer
        } else {
            logger.debug("${parentNode.name}: Creating new staging buffer with capacity=$instanceBufferSize (${ubo.getSize()} x ${parentNode.instances.size})")
            val buffer = BufferUtils.allocateByte((1.2 * instanceBufferSize).toInt())

            state.vertexBuffers["instanceStaging"] = buffer
            buffer
        }

        logger.trace("{}: Staging buffer position, {}, cap={}", parentNode.name, stagingBuffer.position(), stagingBuffer.capacity())

        val index = AtomicInteger(0)
        instances.parallelStream().forEach { node ->
            if(node.visible) {
                node.updateWorld(true, false)

                stagingBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).run {
                    ubo.populateParallel(this,
                        offset = index.getAndIncrement() * ubo.getSize() * 1L,
                        elements = node.instancedProperties)
                }
            }
        }

        stagingBuffer.position(stagingBuffer.limit())
        stagingBuffer.flip()

        val instanceBuffer = state.additionalBufferIds.getOrPut("instance") {
            logger.debug("Instance buffer for ${parentNode.name} needs to be reallocated due to insufficient size ($instanceBufferSize vs ${state.vertexBuffers["instance"]?.capacity() ?: "<not allocated yet>"})")

            val bufferArray = intArrayOf(0)
            gl.glGenBuffers(1, bufferArray, 0)

            gl.glBindVertexArray(state.mVertexArrayObject[0])
            gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, bufferArray[0])

            // instance data starts after vertex, normal, texcoord
            val locationBase = 3
            var location = locationBase
            var baseOffset = 0L
            val stride = parentNode.instances.first().instancedProperties.map {
                val res = it.value.invoke()
                ubo.getSizeAndAlignment(res).first
            }.sum()

            parentNode.instances.first().instancedProperties.entries.forEachIndexed { locationOffset, element ->
                val result = element.value.invoke()
                val sizeAlignment = ubo.getSizeAndAlignment(result)
                location += locationOffset

                val glType = when(result.javaClass) {
                    java.lang.Integer::class.java,
                    Int::class.java -> GL4.GL_INT

                    java.lang.Float::class.java,
                    Float::class.java -> GL4.GL_FLOAT

                    java.lang.Boolean::class.java,
                    Boolean::class.java -> GL4.GL_INT

                    Matrix4f::class.java -> GL4.GL_FLOAT
                    Vector3f::class.java -> GL4.GL_FLOAT

                    else -> { logger.error("Don't know how to serialise ${result.javaClass} for instancing."); GL4.GL_FLOAT }
                }

                val count = when (result) {
                    is Matrix4f -> 4
                    is Vector2f -> 2
                    is Vector3f -> 3
                    is Vector4f -> 4
                    is Vector2i -> 2
                    is Vector3i -> 3
                    is Vector4i -> 4
                    else -> { logger.error("Don't know element size of ${result.javaClass} for instancing."); 1 }
                }

                val necessaryAttributes = if(result is Matrix4f) {
                    4 * 4 / count
                } else {
                    1
                }

                logger.trace("{} needs {} locations with {} elements:", result.javaClass, necessaryAttributes, count)
                (0 until necessaryAttributes).forEach { attributeLocation ->
                    //                    val stride = sizeAlignment.first
                    val offset = baseOffset + 1L * attributeLocation * (sizeAlignment.first/necessaryAttributes)

                    logger.trace("{}, stride={}, offset={}",
                        location + attributeLocation,
                        stride,
                        offset)

                    gl.glEnableVertexAttribArray(location + attributeLocation)

                    // glVertexAttribPoint takes parameters:
                    // * index: attribute location
                    // * size: element count per location
                    // * type: the OpenGL type
                    // * normalized: whether the OpenGL type should be taken as normalized
                    // * stride: the stride between different occupants in the instance array
                    // * pointer_buffer_offset: the offset of the current element (e.g. matrix row) with respect
                    //   to the start of the element in the instance array
                    gl.glVertexAttribPointer(location + attributeLocation, count, glType, false,
                        stride, offset)
                    gl.glVertexAttribDivisor(location + attributeLocation, 1)

                    if(attributeLocation == necessaryAttributes - 1) {
                        baseOffset += sizeAlignment.first
                    }
                }

                location += necessaryAttributes - 1
            }

            gl.glBindVertexArray(0)

            state.additionalBufferIds["instance"] = bufferArray[0]
            bufferArray[0]
        }

        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, instanceBuffer)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER, instanceBufferSize.toLong(), stagingBuffer, GL4.GL_DYNAMIC_DRAW)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)

        state.instanceCount = index.get()
        logger.trace("Updated instance buffer, {parentNode.name} has {} instances.", parentNode.name, state.instanceCount)

        return state
    }

    protected fun destroyNode(node: Node) {
        node.metadata.remove("OpenGLRenderer")
        val s = node.metadata["OpenGLRenderer"] as? OpenGLObjectState ?: return

        gl.glDeleteBuffers(s.mVertexBuffers.size, s.mVertexBuffers, 0)
        gl.glDeleteBuffers(1, s.mIndexBuffer, 0)

        s.additionalBufferIds.forEach { _, id ->
            gl.glDeleteBuffers(1, intArrayOf(id), 0)
        }

        node.initialized = false
    }

    protected var previousSceneObjects: HashSet<Node> = HashSet(256)
    /**
     * Renders the [Scene].
     *
     * The general rendering workflow works like this:
     *
     * 1) All visible elements of the Scene are gathered into the renderOrderList, based on their position
     * 2) Nodes that are an instance of another Node, as indicated by their instanceOf property, are gathered
     *    into the instanceGroups map.
     * 3) The eye-dependent geometry buffers are cleared, both color and depth buffers.
     * 4) First for the non-instanced Nodes, then for the instanced Nodes the following steps are executed
     *    for each eye:
     *
     *      i) The world state of the given Node is updated
     *     ii) Model, view, and model-view-projection matrices are calculated for each. If a HMD is present,
     *         the transformation coming from that is taken into account.
     *    iii) The Node's geometry is updated, if necessary.
     *     iv) The eye's geometry buffer is activated and the Node drawn into it.
     *
     * 5) The deferred shading pass is executed, together with eventual post-processing steps, such as SSAO.
     * 6) If HDR is active, Exposure/Gamma tone mapping is performed. Else, this part is skipped.
     * 7) The resulting image is drawn to the screen, or -- if a HMD is present -- submitted to the OpenVR
     *    compositor.
     */
    override fun render(activeCamera: Camera, sceneNodes: List<Node>) {
        currentObserver = activeCamera
        currentSceneNodes = sceneNodes
        renderCalled = true
    }

    var currentObserver: Camera? = null
    var currentSceneNodes: List<Node> = emptyList()

    @Synchronized fun renderInternal() = runBlocking {
        if(!initialized || !renderCalled) {
            return@runBlocking
        }

        if (scene.children.count() == 0 || !scene.initialized) {
            initializeScene()
            return@runBlocking
        }

        val newTime = System.nanoTime()
        lastFrameTime = (System.nanoTime() - currentTime)/1e6f
        currentTime = newTime

        val stats = hub?.get(SceneryElement.Statistics) as? Statistics

        if (shouldClose) {
            initialized = false
            return@runBlocking
        }

        val running = hub?.getApplication()?.running ?: true

        embedIn?.let {
            resizeHandler.queryResize()
        }

        if (scene.children.count() == 0 || renderpasses.isEmpty() || mustRecreateFramebuffers || !running) {
            delay(200)
            return@runBlocking
        }

        val cam = currentObserver ?: return@runBlocking
        val sceneObjects = currentSceneNodes

        val startUboUpdate = System.nanoTime()
        val ubosUpdated = updateDefaultUBOs(cam)
        stats?.add("OpenGLRenderer.updateUBOs", System.nanoTime() - startUboUpdate)

        var sceneUpdated = true
        if(pushMode) {
            val actualSceneObjects = sceneObjects.toHashSet()
            sceneUpdated = actualSceneObjects != previousSceneObjects

            previousSceneObjects = actualSceneObjects
        }

        val startInstanceUpdate = System.nanoTime()
        val instancesUpdated = updateInstanceBuffers(sceneObjects)
        stats?.add("OpenGLRenderer.updateInstanceBuffers", System.nanoTime() - startInstanceUpdate)

//        if(pushMode) {
//            logger.info("Push mode: ubosUpdated={} sceneUpdated={} screenshotRequested={} instancesUpdated={}", ubosUpdated, sceneUpdated, screenshotRequested, instancesUpdated)
//        }
        if(pushMode && !ubosUpdated && !sceneUpdated && !screenshotRequested && !recordMovie && !instancesUpdated) {
            if(updateLatch == null) {
                updateLatch = CountDownLatch(4)
            }

            logger.trace("UBOs have not been updated, returning ({})", updateLatch?.count)

            if(updateLatch?.count == 0L) {
//                val animator = when {
//                    cglWindow != null -> cglWindow?.glAutoDrawable?.animator
//                    drawable != null -> drawable?.animator
//                    else -> null
//                } as? FPSAnimator
//
//                if(animator != null && animator.fps > 15) {
//                    animator.stop()
//                    animator.fps = 15
//                    animator.start()
//                }

                delay(15)
                return@runBlocking
            }
        }

        if(ubosUpdated || sceneUpdated || screenshotRequested || recordMovie) {
            updateLatch = null
        }

        flow.forEach { t ->
            if(logger.isDebugEnabled || logger.isTraceEnabled) {
                val error = gl.glGetError()

                if (error != 0) {
                    throw Exception("OpenGL error: $error")
                }
            }

            val pass = renderpasses.getValue(t)
            logger.trace("Running pass {}", pass.passName)
            val startPass = System.nanoTime()

            if (pass.passConfig.blitInputs) {
                pass.inputs.forEach { name, input ->
                    val targetName = name.substringAfter(".")
                    if(name.contains(".") && input.getTextureType(targetName) == 0) {
                        logger.trace("Blitting {} into {} (color only)", targetName, pass.output.values.first().id)
                        blitFramebuffers(input, pass.output.values.firstOrNull(),
                            pass.openglMetadata.viewport.area, pass.openglMetadata.viewport.area, colorOnly = true, sourceName = name.substringAfter("."))
                    } else if(name.contains(".") && input.getTextureType(targetName) == 1) {
                        logger.trace("Blitting {} into {} (depth only)", targetName, pass.output.values.first().id)
                        blitFramebuffers(input, pass.output.values.firstOrNull(),
                            pass.openglMetadata.viewport.area, pass.openglMetadata.viewport.area, depthOnly = true)
                    } else {
                        logger.trace("Blitting {} into {}", targetName, pass.output.values.first().id)
                        blitFramebuffers(input, pass.output.values.firstOrNull(),
                            pass.openglMetadata.viewport.area, pass.openglMetadata.viewport.area)
                    }
                }
            }

            if (pass.output.isNotEmpty()) {
                pass.output.values.first().setDrawBuffers(gl)
            } else {
                gl.glBindFramebuffer(GL4.GL_FRAMEBUFFER, 0)
            }

            // bind framebuffers to texture units and determine total number
            pass.inputs.values.reversed().fold(0) { acc, fb -> acc + fb.bindTexturesToUnitsWithOffset(gl, acc) }

            gl.glViewport(
                pass.openglMetadata.viewport.area.offsetX,
                pass.openglMetadata.viewport.area.offsetY,
                pass.openglMetadata.viewport.area.width,
                pass.openglMetadata.viewport.area.height)

            gl.glScissor(
                pass.openglMetadata.scissor.offsetX,
                pass.openglMetadata.scissor.offsetY,
                pass.openglMetadata.scissor.width,
                pass.openglMetadata.scissor.height
            )

            gl.glEnable(GL4.GL_SCISSOR_TEST)

            gl.glClearColor(
                pass.openglMetadata.clearValues.clearColor.x(),
                pass.openglMetadata.clearValues.clearColor.y(),
                pass.openglMetadata.clearValues.clearColor.z(),
                pass.openglMetadata.clearValues.clearColor.w())

            if (!pass.passConfig.blitInputs) {
                pass.output.values.forEach {
                    if (it.hasDepthAttachment()) {
                        gl.glClear(GL4.GL_DEPTH_BUFFER_BIT)
                    }
                }
                gl.glClear(GL4.GL_COLOR_BUFFER_BIT)
            }

            gl.glDisable(GL4.GL_SCISSOR_TEST)

            gl.glDepthRange(
                pass.openglMetadata.viewport.minDepth.toDouble(),
                pass.openglMetadata.viewport.maxDepth.toDouble())

            if (pass.passConfig.type == RenderConfigReader.RenderpassType.geometry ||
                pass.passConfig.type == RenderConfigReader.RenderpassType.lights) {

                gl.glEnable(GL4.GL_DEPTH_TEST)
                gl.glEnable(GL4.GL_CULL_FACE)

                if (pass.passConfig.renderTransparent) {
                    gl.glEnable(GL4.GL_BLEND)
                    gl.glBlendFuncSeparate(
                        pass.passConfig.srcColorBlendFactor.toOpenGL(),
                        pass.passConfig.dstColorBlendFactor.toOpenGL(),
                        pass.passConfig.srcAlphaBlendFactor.toOpenGL(),
                        pass.passConfig.dstAlphaBlendFactor.toOpenGL())

                    gl.glBlendEquationSeparate(
                        pass.passConfig.colorBlendOp.toOpenGL(),
                        pass.passConfig.alphaBlendOp.toOpenGL()
                    )
                } else {
                    gl.glDisable(GL4.GL_BLEND)
                }

                val actualObjects = if(pass.passConfig.type == RenderConfigReader.RenderpassType.geometry) {
                    sceneObjects.filter { it !is Light }.toMutableList()
                } else {
                    sceneObjects.filter { it is Light }.toMutableList()
                }

                actualObjects.sortBy { (it as? RenderingOrder)?.renderingOrder }

                var currentShader: OpenGLShaderProgram? = null

                val seenDelegates = ArrayList<Node>(5)
                actualObjects.forEach renderLoop@ { node ->
                    val n = if(node is DelegatesRendering) {
                        val delegate = node.delegate
                        if(node.delegationType == DelegationType.OncePerDelegate && delegate != null) {
                            if(delegate in seenDelegates) {
                                return@renderLoop
                            } else {
                                seenDelegates.add(delegate)
                                delegate
                            }
                        } else {
                            node.delegate ?: return@renderLoop
                        }
                    } else {
                        node
                    }

                    if (pass.passConfig.renderOpaque && n.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                        return@renderLoop
                    }

                    if (pass.passConfig.renderTransparent && !n.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                        return@renderLoop
                    }

                    gl.glEnable(GL4.GL_CULL_FACE)
                    when(n.material.cullingMode) {
                        Material.CullingMode.None -> gl.glDisable(GL4.GL_CULL_FACE)
                        Material.CullingMode.Front -> gl.glCullFace(GL4.GL_FRONT)
                        Material.CullingMode.Back -> gl.glCullFace(GL4.GL_BACK)
                        Material.CullingMode.FrontAndBack -> gl.glCullFace(GL4.GL_FRONT_AND_BACK)
                    }

                   val depthTest = when(n.material.depthTest) {
                        Material.DepthTest.Less -> GL4.GL_LESS
                        Material.DepthTest.Greater -> GL4.GL_GREATER
                        Material.DepthTest.LessEqual -> GL4.GL_LEQUAL
                        Material.DepthTest.GreaterEqual -> GL4.GL_GEQUAL
                        Material.DepthTest.Always -> GL4.GL_ALWAYS
                        Material.DepthTest.Never -> GL4.GL_NEVER
                        Material.DepthTest.Equal -> GL4.GL_EQUAL
                    }

                    gl.glDepthFunc(depthTest)

                    if(n.material.wireframe) {
                        gl.glPolygonMode(GL4.GL_FRONT_AND_BACK, GL4.GL_LINE)
                    } else {
                        gl.glPolygonMode(GL4.GL_FRONT_AND_BACK, GL4.GL_FILL)
                    }

                    if (n.material.blending.transparent) {
                        with(n.material.blending) {
                            gl.glBlendFuncSeparate(
                                sourceColorBlendFactor.toOpenGL(),
                                destinationColorBlendFactor.toOpenGL(),
                                sourceAlphaBlendFactor.toOpenGL(),
                                destinationAlphaBlendFactor.toOpenGL()
                            )

                            gl.glBlendEquationSeparate(
                                colorBlending.toOpenGL(),
                                alphaBlending.toOpenGL()
                            )
                        }
                    }

                    if (!n.metadata.containsKey("OpenGLRenderer") || !n.initialized) {
                        n.metadata["OpenGLRenderer"] = OpenGLObjectState()
                        initializeNode(n)
                        return@renderLoop
                    }

                    val s = getOpenGLObjectStateFromNode(n)


                    val shader = s.shader ?: pass.defaultShader!!

                    if(currentShader != shader) {
                        shader.use(gl)
                    }

                    currentShader = shader

                    if (renderConfig.stereoEnabled) {
                        shader.getUniform("currentEye.eye").setInt(pass.openglMetadata.eye)
                    }

                    var unit = 0
                    pass.inputs.keys.reversed().forEach { name ->
                        renderConfig.rendertargets[name.substringBefore(".")]?.attachments?.forEach {
                            shader.getUniform("Input" + it.key).setInt(unit)
                            unit++
                        }
                    }

                    val unboundSamplers = (unit until maxTextureUnits).toMutableList()
                    var maxSamplerIndex = 0
                    val textures = s.textures.entries.groupBy { Texture.objectTextures.contains(it.key) }
                    val objectTextures = textures[true]
                    val others = textures[false]

                    objectTextures?.forEach { texture ->
                        val samplerIndex = textureTypeToUnit(pass, texture.key)
                        maxSamplerIndex = max(samplerIndex, maxSamplerIndex)

                        @Suppress("SENSELESS_COMPARISON")
                        if (texture.value != null) {
                            gl.glActiveTexture(GL4.GL_TEXTURE0 + samplerIndex)

                            val target = if (texture.value.depth > 1) {
                                GL4.GL_TEXTURE_3D
                            } else {
                                GL4.GL_TEXTURE_2D
                            }

                            gl.glBindTexture(target, texture.value.id)
                            shader.getUniform(textureTypeToArrayName(texture.key)).setInt(samplerIndex)
                            unboundSamplers.remove(samplerIndex)
                        }
                    }

                    var samplerIndex = maxSamplerIndex
                    others?.forEach { texture ->
                        @Suppress("SENSELESS_COMPARISON")
                        if(texture.value != null) {
                            val minIndex = unboundSamplers.minOrNull() ?: maxSamplerIndex
                            gl.glActiveTexture(GL4.GL_TEXTURE0 + minIndex)

                            val target = if (texture.value.depth > 1) {
                                GL4.GL_TEXTURE_3D
                            } else {
                                GL4.GL_TEXTURE_2D
                            }

                            gl.glBindTexture(target, texture.value.id)
                            shader.getUniform(texture.key).setInt(minIndex)
                            samplerIndex++
                            unboundSamplers.remove(minIndex)
                        }
                    }

                    var binding = 0

                    (s.UBOs + pass.UBOs).forEach { name, ubo ->
                        val actualName = if (name.contains("ShaderParameters")) {
                            "ShaderParameters"
                        } else {
                            name
                        }

                        if(shader.uboSpecs.containsKey(actualName) && shader.isValid()) {
                            val index = shader.getUniformBlockIndex(actualName)
                            logger.trace("Binding {} for {}, index={}, binding={}, size={}", actualName, n.name, index, binding, ubo.getSize())

                            if (index == -1) {
                                logger.error("Failed to bind UBO $actualName for ${n.name} to $binding")
                            } else {
                                gl.glUniformBlockBinding(shader.id, index, binding)
                                gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                                    ubo.backingBuffer!!.id[0], 1L * ubo.offset, 1L * ubo.getSize())
                                binding++
                            }
                        }
                    }

                    arrayOf("VRParameters" to buffers.VRParameters,
                        "LightParameters" to buffers.LightParameters).forEach uboBinding@ { b ->
                        val buffer = b.second
                        val name = b.first

                        if (shader.uboSpecs.containsKey(name) && shader.isValid()) {
                            val index = shader.getUniformBlockIndex(name)

                            if (index == -1) {
                                logger.error("Failed to bind shader parameter UBO $name for ${pass.passName} to $binding, though it is required by the shader")
                            } else {
                                gl.glUniformBlockBinding(shader.id, index, binding)
                                gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                                    buffer.id[0],
                                    0L, buffer.buffer.remaining().toLong())


                                binding++
                            }
                        }
                    }

                    if(n.instances.size > 0) {
                        drawNodeInstanced(n)
                    } else {
                        drawNode(n)
                    }
                }
            } else {
                gl.glDisable(GL4.GL_CULL_FACE)

                if (pass.output.any { it.value.hasDepthAttachment() }) {
                    gl.glEnable(GL4.GL_DEPTH_TEST)
                } else {
                    gl.glDisable(GL4.GL_DEPTH_TEST)
                }

                if (pass.passConfig.renderTransparent) {
                    gl.glEnable(GL4.GL_BLEND)

                    gl.glBlendFuncSeparate(
                        pass.passConfig.srcColorBlendFactor.toOpenGL(),
                        pass.passConfig.dstColorBlendFactor.toOpenGL(),
                        pass.passConfig.srcAlphaBlendFactor.toOpenGL(),
                        pass.passConfig.dstAlphaBlendFactor.toOpenGL())

                    gl.glBlendEquationSeparate(
                        pass.passConfig.colorBlendOp.toOpenGL(),
                        pass.passConfig.alphaBlendOp.toOpenGL()
                    )
                } else {
                    gl.glDisable(GL4.GL_BLEND)
                }


                pass.defaultShader?.let { shader ->
                    shader.use(gl)

                    var unit = 0
                    pass.inputs.keys.reversed().forEach { name ->
                        renderConfig.rendertargets[name.substringBefore(".")]?.attachments?.forEach {
                            shader.getUniform("Input" + it.key).setInt(unit)
                            unit++
                        }
                    }

                    var binding = 0
                    pass.UBOs.forEach { name, ubo ->
                        val actualName = if (name.contains("ShaderParameters")) {
                            "ShaderParameters"
                        } else {
                            name
                        }

                        val index = shader.getUniformBlockIndex(actualName)
                        gl.glUniformBlockBinding(shader.id, index, binding)
                        gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                            ubo.backingBuffer!!.id[0],
                            1L * ubo.offset, 1L * ubo.getSize())

                        if (index == -1) {
                            logger.error("Failed to bind shader parameter UBO $actualName for ${pass.passName} to $binding")
                        }
                        binding++
                    }

                    arrayOf("LightParameters" to buffers.LightParameters,
                        "VRParameters" to buffers.VRParameters).forEach { b ->
                        val name = b.first
                        val buffer = b.second
                        if (shader.uboSpecs.containsKey(name)) {
                            val index = shader.getUniformBlockIndex(name)
                            gl.glUniformBlockBinding(shader.id, index, binding)
                            gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                                buffer.id[0],
                                0L, buffer.buffer.remaining().toLong())

                            if (index == -1) {
                                logger.error("Failed to bind shader parameter UBO $name for ${pass.passName} to $binding, though it is required by the shader")
                            }

                            binding++
                        }
                    }

                    renderFullscreenQuad(shader)
                }
            }

            stats?.add("Renderer.$t.renderTiming", System.nanoTime() - startPass)
        }

        if(logger.isDebugEnabled || logger.isTraceEnabled) {
            val error = gl.glGetError()

            if (error != 0) {
                throw Exception("OpenGL error: $error")
            }
        }

        logger.trace("Running viewport pass")
        val startPass = System.nanoTime()
        val viewportPass = renderpasses.getValue(flow.last())
        gl.glBindFramebuffer(GL4.GL_DRAW_FRAMEBUFFER, 0)

        blitFramebuffers(viewportPass.output.values.first(), null,
            OpenGLRenderpass.Rect2D(
                settings.get("Renderer.${viewportPass.passName}.displayWidth"),
                settings.get("Renderer.${viewportPass.passName}.displayHeight"), 0, 0),
            OpenGLRenderpass.Rect2D(window.width, window.height, 0, 0))

        // submit to OpenVR if attached
        if(hub?.getWorkingHMDDisplay()?.hasCompositor() == true && !mustRecreateFramebuffers) {
            hub?.getWorkingHMDDisplay()?.wantsVR(settings)?.submitToCompositor(
                viewportPass.output.values.first().getTextureId("Viewport"))
        }

        val w = viewportPass.output.values.first().width
        val h = viewportPass.output.values.first().height

        if((embedIn != null && embedIn !is SceneryJPanel) || recordMovie) {
            if (shouldClose || mustRecreateFramebuffers) {
                encoder?.finish()

                encoder = null
                return@runBlocking
            }

            if (recordMovie && (encoder == null || encoder?.frameWidth != w || encoder?.frameHeight != h)) {
                val file = SystemHelpers.addFileCounter(if(movieFilename == "") {
                    File(System.getProperty("user.home"), "Desktop" + File.separator + "$applicationName - ${SystemHelpers.formatDateTime()}.mp4")
                } else {
                    File(movieFilename)
                }, false)

                val supersamplingFactor = getSupersamplingFactor(cglWindow)
                encoder = H264Encoder(
                    (supersamplingFactor * window.width).toInt(),
                    (supersamplingFactor * window.height).toInt(),
                    file.absolutePath,
                    hub = hub)
            }

            readIndex = (readIndex + 1) % pboCount
            updateIndex = (updateIndex + 1) % pboCount

            if (pbos.any { it == 0 } || mustRecreateFramebuffers) {
                gl.glGenBuffers(pboCount, pbos, 0)

                pbos.forEachIndexed { index, pbo ->
                    gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbo)
                    gl.glBufferData(GL4.GL_PIXEL_PACK_BUFFER, w * h * 4L, null, GL4.GL_STREAM_READ)

                    if(pboBuffers[index] != null) {
                        MemoryUtil.memFree(pboBuffers[index])
                        pboBuffers[index] = null
                    }
                }

                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0)
            }

            pboBuffers.forEachIndexed { i, _ ->
                if(pboBuffers[i] == null) {
                    pboBuffers[i] = MemoryUtil.memAlloc(4 * w * h)
                }
            }

            viewportPass.output.values.first().setReadBuffers(gl)

            val startUpdate = System.nanoTime()
            if(frames < pboCount) {
                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbos[updateIndex])
                gl.glReadPixels(0, 0, w, h, GL4.GL_BGRA, GL4.GL_UNSIGNED_BYTE, 0)
            } else {
                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbos[updateIndex])

                val readBuffer = gl.glMapBuffer(GL4.GL_PIXEL_PACK_BUFFER, GL4.GL_READ_ONLY)
                MemoryUtil.memCopy(readBuffer, pboBuffers[readIndex]!!)
                gl.glUnmapBuffer(GL4.GL_PIXEL_PACK_BUFFER)

                gl.glReadPixels(0, 0, w, h, GL4.GL_BGRA, GL4.GL_UNSIGNED_BYTE, 0)
            }

            if (!mustRecreateFramebuffers && frames > pboCount) {
                embedIn?.let { embedPanel ->
                    pboBuffers[readIndex]?.let {
                        val id = viewportPass.output.values.first().getTextureId("Viewport")
                        embedPanel.update(it, id = id)
                    }
                }

                encoder?.let { e ->
                    pboBuffers[readIndex]?.let {
                        e.encodeFrame(it, flip = true)
                    }
                }
            }
            val updateDuration = (System.nanoTime() - startUpdate)*1.0f
            stats?.add("Renderer.updateEmbed", updateDuration, true)

            gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0)
        }


        val request = try {
            imageRequests.poll()
        } catch(e: NoSuchElementException) {
            null
        }

        if ((screenshotRequested || request != null) && joglDrawable != null) {
            try {
                val readBufferUtil = AWTGLReadBufferUtil(joglDrawable!!.glProfile, false)
                val image = readBufferUtil.readPixelsToBufferedImage(gl, true)
                val file = SystemHelpers.addFileCounter(if(screenshotFilename == "") {
                    File(System.getProperty("user.home"), "Desktop" + File.separator + "$applicationName - ${SystemHelpers.formatDateTime()}.png")
                } else {
                    File(screenshotFilename)
                }, screenshotOverwriteExisting)

                if(request != null) {
                    request.width = window.width
                    request.height = window.height
                    val data = (image.raster.dataBuffer as DataBufferInt).data
                    val tmp = ByteBuffer.allocate(data.size * 4)
                    data.forEach { value ->
                        val r = (value shr 16).toByte()
                        val g = (value shr 8).toByte()
                        val b = value.toByte()
                        tmp.put(0)
                        tmp.put(b)
                        tmp.put(g)
                        tmp.put(r)
                    }

                    request.data = tmp.array()
                }

                if(screenshotRequested && image != null) {
                    ImageIO.write(image, "png", file)
                    logger.info("Screenshot saved to ${file.absolutePath}")
                }
            } catch (e: Exception) {
                logger.error("Unable to take screenshot: ")
                e.printStackTrace()
            }

            screenshotOverwriteExisting = false
            screenshotRequested = false
        }

        postRenderLambdas.forEach {
            it.invoke()
        }

        stats?.add("Renderer.${flow.last()}.renderTiming", System.nanoTime() - startPass)

        updateLatch?.countDown()
        firstImageReady = true
        frames++
        totalFrames++
        framesPerSec++
    }

    /**
     * Renders a fullscreen quad, using from an on-the-fly generated
     * Node that is saved in [nodeStore], with the [GLProgram]'s ID.
     *
     * @param[program] The [GLProgram] to draw into the fullscreen quad.
     */
    fun renderFullscreenQuad(program: OpenGLShaderProgram) {
        val quad: Node
        val quadName = "fullscreenQuad-${program.id}"

        quad = nodeStore.getOrPut(quadName) {
            val q = Plane(Vector3f(1.0f, 1.0f, 0.0f))

            q.metadata["OpenGLRenderer"] = OpenGLObjectState()
            initializeNode(q)

            q
        }

        drawNode(quad, count = 3)
        program.gl.glBindTexture(GL4.GL_TEXTURE_2D, 0)
    }

    /**
     * Initializes a given [Node].
     *
     * This function initializes a Node, equipping its metadata with an [OpenGLObjectState],
     * generating VAOs and VBOs. If the Node has a [Material] assigned, a [GLProgram] fitting
     * this Material will be used. Else, a default GLProgram will be used.
     *
     * For the assigned Material case, the GLProgram is derived either from the class name of the
     * Node (if useClassDerivedShader is set), or from a set [ShaderMaterial] which may define
     * the whole shader pipeline for the Node.
     *
     * If the [Node] implements [HasGeometry], it's geometry is also initialized by this function.
     *
     * @param[n]: The [Node] to initialise.
     * @return True if the initialisation went alright, False if it failed.
     */
    @Synchronized fun initializeNode(n: Node): Boolean {
        val node = if(n is DelegatesRendering) {
            n.delegate ?: return false
        } else {
            n
        }

        if(!node.lock.tryLock()) {
            return false
        }

        if(node.rendererMetadata() == null) {
            node.metadata["OpenGLRenderer"] = OpenGLObjectState()
        }

        val s = node.metadata["OpenGLRenderer"] as OpenGLObjectState

        if (s.initialized) {
            return true
        }

        // generate VAO for attachment of VBO and indices
        gl.glGenVertexArrays(1, s.mVertexArrayObject, 0)

        // generate three VBOs for coords, normals, texcoords
        gl.glGenBuffers(3, s.mVertexBuffers, 0)
        gl.glGenBuffers(1, s.mIndexBuffer, 0)

        when {
            node.material is ShaderMaterial -> {
                val shaders = (node.material as ShaderMaterial).shaders

                try {
                    s.shader = prepareShaderProgram(shaders)
                } catch (e: ShaderCompilationException) {
                    logger.warn("Shader compilation for node ${node.name} with shaders $shaders failed, falling back to default shaders.")
                    logger.warn("Shader compiler error was: ${e.message}")
                }
            }
            else -> s.shader = null
        }

        if (node is HasGeometry) {
            setVerticesAndCreateBufferForNode(node)
            setNormalsAndCreateBufferForNode(node)

            if (node.texcoords.limit() > 0) {
                setTextureCoordsAndCreateBufferForNode(node)
            }

            if (node.indices.limit() > 0) {
                setIndicesAndCreateBufferForNode(node)
            }

        }

        s.materialHash = node.material.materialHashCode()

        val matricesUbo = OpenGLUBO(backingBuffer = buffers.UBOs)
        with(matricesUbo) {
            name = "Matrices"
            add("ModelMatrix", { node.world })
            add("NormalMatrix", { Matrix4f(node.world).invert().transpose() })
            add("isBillboard", { node.isBillboard.toInt() })

            sceneUBOs.add(node)

            s.UBOs.put("Matrices", this)
        }

        loadTexturesForNode(node, s)

        val materialUbo = OpenGLUBO(backingBuffer = buffers.UBOs)

        with(materialUbo) {
            name = "MaterialProperties"
            add("materialType", { node.materialToMaterialType() })
            add("Ka", { node.material.ambient })
            add("Kd", { node.material.diffuse })
            add("Ks", { node.material.specular })
            add("Roughness", { node.material.roughness })
            add("Metallic", { node.material.metallic })
            add("Opacity", { node.material.blending.opacity })

            s.UBOs.put("MaterialProperties", this)
        }

        if (node.javaClass.kotlin.memberProperties.filter { it.findAnnotation<ShaderProperty>() != null }.count() > 0) {
            val shaderPropertyUBO = OpenGLUBO(backingBuffer = buffers.ShaderProperties)
            with(shaderPropertyUBO) {
                name = "ShaderProperties"

                val shader = if (node.material is ShaderMaterial) {
                    s.shader
                } else {
                    renderpasses.filter {
                        (it.value.passConfig.type == RenderConfigReader.RenderpassType.geometry || it.value.passConfig.type == RenderConfigReader.RenderpassType.lights)
                            && it.value.passConfig.renderTransparent == node.material.blending.transparent
                    }.entries.firstOrNull()?.value?.defaultShader
                }

                logger.debug("Shader properties are: ${shader?.getShaderPropertyOrder()}")
                shader?.getShaderPropertyOrder()?.forEach { name, offset ->
                    add(name, { node.getShaderProperty(name) ?: 0 }, offset)
                }
            }

            s.UBOs["ShaderProperties"] = shaderPropertyUBO
        }

        s.initialized = true
        node.initialized = true
        node.metadata[className] = s

        s.initialized = true
        node.lock.unlock()
        return true
    }

    private val defaultTextureNames = arrayOf("ambient", "diffuse", "specular", "normal", "alphamask", "displacement")

    private fun Node.materialToMaterialType(): Int {
        var materialType = 0
        val s = this.metadata["OpenGLRenderer"] as? OpenGLObjectState ?: return 0



        if (this.material.textures.containsKey("ambient") && !s.defaultTexturesFor.contains("ambient")) {
            materialType = materialType or MATERIAL_HAS_AMBIENT
        }

        if (this.material.textures.containsKey("diffuse") && !s.defaultTexturesFor.contains("diffuse")) {
            materialType = materialType or MATERIAL_HAS_DIFFUSE
        }

        if (this.material.textures.containsKey("specular") && !s.defaultTexturesFor.contains("specular")) {
            materialType = materialType or MATERIAL_HAS_SPECULAR
        }

        if (this.material.textures.containsKey("normal") && !s.defaultTexturesFor.contains("normal")) {
            materialType = materialType or MATERIAL_HAS_NORMAL
        }

        if (this.material.textures.containsKey("alphamask") && !s.defaultTexturesFor.contains("alphamask")) {
            materialType = materialType or MATERIAL_HAS_ALPHAMASK
        }

        return materialType
    }

    /**
     * Initializes a default set of textures that the renderer can fall back to and provide a non-intrusive
     * hint to the user that a texture could not be loaded.
     */
    private fun prepareDefaultTextures() {
        val t = GLTexture.loadFromFile(gl, Renderer::class.java.getResourceAsStream("DefaultTexture.png"), "png", false, true, 8)
        if(t == null) {
            logger.error("Could not load default texture! This indicates a serious issue.")
        } else {
            textureCache["DefaultTexture"] = t
        }
    }

    /**
     * Returns true if the current [GLTexture] can be reused to store the information in the [Texture]
     * [other]. Returns false otherwise.
     */
    protected fun GLTexture.canBeReused(other: Texture): Boolean {
        return this.width == other.dimensions.x() &&
            this.height == other.dimensions.y() &&
            this.depth == other.dimensions.z() &&
            this.nativeType.equivalentTo(other.type)
    }

    private fun GLTypeEnum.equivalentTo(type: NumericType<*>): Boolean {
        return when {
            this == GLTypeEnum.UnsignedByte && type is UnsignedByteType -> true
            this == GLTypeEnum.Byte && type is ByteType -> true

            this == GLTypeEnum.UnsignedShort && type is UnsignedShortType -> true
            this == GLTypeEnum.Short && type is ShortType -> true

            this == GLTypeEnum.UnsignedInt && type is UnsignedIntType -> true
            this == GLTypeEnum.Int && type is IntType -> true

            this == GLTypeEnum.Float && type is FloatType -> true
            this == GLTypeEnum.Double && type is DoubleType -> true
            else -> false
        }
    }

    @Suppress("unused")
    private fun dumpTextureToFile(gl: GL4, name: String, texture: GLTexture) {
        val filename = "${name}_${Date().toInstant().epochSecond}.raw"
        val bytes = texture.width*texture.height*texture.depth*texture.channels*texture.bitsPerChannel/8
        logger.info("Dumping $name to $filename ($bytes bytes)")
        val buffer = MemoryUtil.memAlloc(bytes)
        gl.glPixelStorei(GL4.GL_PACK_ALIGNMENT, 1)
        texture.bind()
        gl.glGetTexImage(texture.textureTarget, 0, texture.format, texture.type, buffer)
        texture.unbind()

        val stream = FileOutputStream(filename)
        val outChannel = stream.channel
        outChannel.write(buffer)
        logger.info("Written $texture to $stream")
        outChannel.close()
        stream.close()
        MemoryUtil.memFree(buffer)
    }

    private fun RepeatMode.toOpenGL(): Int {
        return when(this) {
            RepeatMode.Repeat -> GL4.GL_REPEAT
            RepeatMode.MirroredRepeat -> GL4.GL_MIRRORED_REPEAT
            RepeatMode.ClampToEdge -> GL4.GL_CLAMP_TO_EDGE
            RepeatMode.ClampToBorder -> GL4.GL_CLAMP_TO_BORDER
        }
    }

    private fun BorderColor.toOpenGL(): FloatArray {
        return when(this) {
            BorderColor.TransparentBlack -> floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
            BorderColor.OpaqueBlack -> floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
            BorderColor.OpaqueWhite -> floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        }
    }

    private fun NumericType<*>.toOpenGL(): GLTypeEnum {
        return when(this) {
            is UnsignedByteType -> GLTypeEnum.UnsignedByte
            is ByteType -> GLTypeEnum.Byte
            is UnsignedShortType -> GLTypeEnum.UnsignedShort
            is ShortType -> GLTypeEnum.Short
            is UnsignedIntType -> GLTypeEnum.UnsignedInt
            is IntType -> GLTypeEnum.Int
            is FloatType -> GLTypeEnum.Float
            is DoubleType -> GLTypeEnum.Double
            else -> throw IllegalStateException("Type ${this.javaClass.simpleName} is not supported as OpenGL texture type")
        }
    }

    /**
     * Loads textures for a [Node]. The textures are loaded from a [Material.textures].
     *
     * @param[node] The [Node] to load textures for.
     * @param[s] The [Node]'s [OpenGLObjectState]
     */
    @Suppress("USELESS_ELVIS")
    private fun loadTexturesForNode(node: Node, s: OpenGLObjectState): Boolean {
        var changed = false
        val last = s.texturesLastSeen
        val now = System.nanoTime()
        node.material.textures.forEachChanged(last) { (type, texture) ->
            changed = true
            logger.debug("Loading texture $texture for ${node.name}")

            val generateMipmaps = Texture.mipmappedObjectTextures.contains(type)
            val contentsNew = texture.contents?.duplicate()
            logger.debug("Dims of $texture: ${texture.dimensions}, mipmaps=$generateMipmaps")

            val mm = generateMipmaps or texture.mipmap
            val miplevels = if (mm && texture.dimensions.z() == 1) {
                1 + floor(ln(max(texture.dimensions.x() * 1.0, texture.dimensions.y() * 1.0)) / ln(2.0)).toInt()
            } else {
                1
            }

            val existingTexture = s.textures[type]
            val t = if(existingTexture != null && existingTexture.canBeReused(texture)) {
                existingTexture
            } else {
                GLTexture(gl, texture.type.toOpenGL(), texture.channels,
                    texture.dimensions.x(),
                    texture.dimensions.y(),
                    texture.dimensions.z() ?: 1,
                    texture.minFilter == Texture.FilteringMode.Linear,
                    miplevels, 32,
                    texture.normalized, renderConfig.sRGB)
            }

            if (mm) {
                t.updateMipMaps()
            }

            t.setRepeatModeS(texture.repeatUVW.first.toOpenGL())
            t.setRepeatModeT(texture.repeatUVW.second.toOpenGL())
            t.setRepeatModeR(texture.repeatUVW.third.toOpenGL())

            t.setTextureBorderColor(texture.borderColor.toOpenGL())

            // textures might have very uneven dimensions, so we adjust GL_UNPACK_ALIGNMENT here correspondingly
            // in case the byte count of the texture is not divisible by it.
            val unpackAlignment = intArrayOf(0)
            gl.glGetIntegerv(GL4.GL_UNPACK_ALIGNMENT, unpackAlignment, 0)

            texture.contents?.let { contents ->
                t.copyFrom(contents.duplicate())
            }

            if (contentsNew != null && texture is UpdatableTexture && !texture.hasConsumableUpdates()) {
                if (contentsNew.remaining() % unpackAlignment[0] == 0 && texture.dimensions.x() % unpackAlignment[0] == 0) {
                    t.copyFrom(contentsNew)
                } else {
                    gl.glPixelStorei(GL4.GL_UNPACK_ALIGNMENT, 1)

                    t.copyFrom(contentsNew)
                }
                gl.glPixelStorei(GL4.GL_UNPACK_ALIGNMENT, unpackAlignment[0])
            }

            if (texture is UpdatableTexture && texture.hasConsumableUpdates()) {
                gl.glPixelStorei(GL4.GL_UNPACK_ALIGNMENT, 1)
                texture.getConsumableUpdates().forEach { update ->
                    t.copyFrom(update.contents,
                        update.extents.w, update.extents.h, update.extents.d,
                        update.extents.x, update.extents.y, update.extents.z, true)
                    update.consumed = true
                }

                texture.clearConsumedUpdates()
                gl.glPixelStorei(GL4.GL_UNPACK_ALIGNMENT, unpackAlignment[0])
            }

            s.textures[type] = t
//                textureCache.put(texture, t)
        }

        // update default textures
        // s.defaultTexturesFor = defaultTextureNames.mapNotNull { if(!s.textures.containsKey(it)) { it } else { null } }.toHashSet()
        s.defaultTexturesFor.clear()
        defaultTextureNames.forEach {
            if (!s.textures.containsKey(it)) {
                s.defaultTexturesFor.add(it)
            }
        }

        s.texturesLastSeen = now
        s.initialized = true
        return changed
    }

    /**
     * Reshape the renderer's viewports
     *
     * This routine is called when a change in window size is detected, e.g. when resizing
     * it manually or toggling fullscreen. This function updates the sizes of all used
     * geometry buffers and will also create new buffers in case vr.Active is changed.
     *
     * This function also clears color and depth buffer bits.
     *
     * @param[newWidth] The resized window's width
     * @param[newHeight] The resized window's height
     */
    override fun reshape(newWidth: Int, newHeight: Int) {
        if (!initialized) {
            return
        }

        lastResizeTimer.cancel()

        lastResizeTimer = Timer()
        lastResizeTimer.schedule(object : TimerTask() {
            override fun run() {
                val surfaceScale = hub?.get<Settings>()?.get("Renderer.SurfaceScale", Vector2f(1.0f, 1.0f))
                    ?: Vector2f(1.0f, 1.0f)

                val panel = embedIn

                if(panel is SceneryJPanel && panel.width != (newWidth/surfaceScale.x()).toInt() && panel.height != (newWidth/surfaceScale.y()).toInt()) {
                    logger.debug("Panel is ${panel.width}x${panel.height} vs $newWidth x $newHeight")
                    window.width = (newWidth * surfaceScale.x()).toInt()
                    window.height = (newHeight * surfaceScale.y()).toInt()
                } else {
                    window.width = newWidth
                    window.height = newHeight
                }

                logger.debug("Resizing window to ${newWidth}x$newHeight")
                mustRecreateFramebuffers = true
            }
        }, WINDOW_RESIZE_TIMEOUT)
    }

    /**
     * Creates VAOs and VBO for a given [Node]'s vertices.
     *
     * @param[node] The [Node] to create the VAO/VBO for.
     */
    fun setVerticesAndCreateBufferForNode(node: Node) {
        updateVertices(node)
    }

    /**
     * Updates a [Node]'s vertices.
     *
     * @param[node] The [Node] to update the vertices for.
     */
    fun updateVertices(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pVertexBuffer: FloatBuffer = (node as HasGeometry).vertices.duplicate()

        s.mStoredPrimitiveCount = pVertexBuffer.remaining() / node.vertexSize

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[0])

        gl.glEnableVertexAttribArray(0)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pVertexBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pVertexBuffer,
            GL4.GL_DYNAMIC_DRAW)

        gl.glVertexAttribPointer(0,
            node.vertexSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Creates VAOs and VBO for a given [Node]'s normals.
     *
     * @param[node] The [Node] to create the normals VBO for.
     */
    fun setNormalsAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pNormalBuffer: FloatBuffer = (node as HasGeometry).normals.duplicate()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[1])

        if (pNormalBuffer.limit() > 0) {
            gl.glEnableVertexAttribArray(1)
            gl.glBufferData(GL4.GL_ARRAY_BUFFER,
                (pNormalBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pNormalBuffer,
                GL4.GL_DYNAMIC_DRAW)

            gl.glVertexAttribPointer(1,
                node.vertexSize,
                GL4.GL_FLOAT,
                false,
                0,
                0)

        }
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Updates a given [Node]'s normals.
     *
     * @param[node] The [Node] whose normals need updating.
     */
    fun updateNormals(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pNormalBuffer: FloatBuffer = (node as HasGeometry).normals.duplicate()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[1])

        gl.glEnableVertexAttribArray(1)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pNormalBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pNormalBuffer,
            GL4.GL_DYNAMIC_DRAW)

        gl.glVertexAttribPointer(1,
            node.vertexSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Creates VAOs and VBO for a given [Node]'s texcoords.
     *
     * @param[node] The [Node] to create the texcoord VBO for.
     */
    fun setTextureCoordsAndCreateBufferForNode(node: Node) {
        updateTextureCoords(node)
    }

    /**
     * Updates a given [Node]'s texcoords.
     *
     * @param[node] The [Node] whose texcoords need updating.
     */
    fun updateTextureCoords(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pTextureCoordsBuffer: FloatBuffer = (node as HasGeometry).texcoords.duplicate()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER,
            s.mVertexBuffers[2])

        gl.glEnableVertexAttribArray(2)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pTextureCoordsBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pTextureCoordsBuffer,
            GL4.GL_DYNAMIC_DRAW)

        gl.glVertexAttribPointer(2,
            node.texcoordSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Creates a index buffer for a given [Node]'s indices.
     *
     * @param[node] The [Node] to create the index buffer for.
     */
    fun setIndicesAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pIndexBuffer: IntBuffer = (node as HasGeometry).indices.duplicate()

        s.mStoredIndexCount = pIndexBuffer.remaining()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, s.mIndexBuffer[0])

        gl.glBufferData(GL4.GL_ELEMENT_ARRAY_BUFFER,
            (pIndexBuffer.remaining() * (Integer.SIZE / java.lang.Byte.SIZE)).toLong(),
            pIndexBuffer,
            GL4.GL_DYNAMIC_DRAW)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /**
     * Updates a given [Node]'s indices.
     *
     * @param[node] The [Node] whose indices need updating.
     */
    fun updateIndices(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pIndexBuffer: IntBuffer = (node as HasGeometry).indices.duplicate()

        s.mStoredIndexCount = pIndexBuffer.remaining()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, s.mIndexBuffer[0])

        gl.glBufferData(GL4.GL_ELEMENT_ARRAY_BUFFER,
            (pIndexBuffer.remaining() * (Integer.SIZE / java.lang.Byte.SIZE)).toLong(),
            pIndexBuffer,
            GL4.GL_DYNAMIC_DRAW)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /**
     * Draws a given [Node], either in element or in index draw mode.
     *
     * @param[node] The node to be drawn.
     * @param[offset] offset in the array or index buffer.
     */
    fun drawNode(node: Node, offset: Int = 0, count: Int? = null) {
        val s = getOpenGLObjectStateFromNode(node)

        if (s.mStoredIndexCount == 0 && s.mStoredPrimitiveCount == 0) {
            return
        }
        logger.trace("Drawing {} with {}, {} primitives, {} indices", node.name, s.shader?.modules?.entries?.joinToString(", "), s.mStoredPrimitiveCount, s.mStoredIndexCount)
        gl.glBindVertexArray(s.mVertexArrayObject[0])

        if (s.mStoredIndexCount > 0) {
            gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER,
                s.mIndexBuffer[0])
            gl.glDrawElements((node as HasGeometry).geometryType.toOpenGLType(),
                count ?: s.mStoredIndexCount,
                GL4.GL_UNSIGNED_INT,
                offset.toLong())

            gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, 0)
        } else {
            gl.glDrawArrays((node as HasGeometry).geometryType.toOpenGLType(), offset, count ?: s.mStoredPrimitiveCount)
        }

//        gl.glUseProgram(0)
//        gl.glBindVertexArray(0)
    }

    /**
     * Draws a given instanced [Node] either in element or in index draw mode.
     *
     * @param[node] The node to be drawn.
     * @param[offset] offset in the array or index buffer.
     */
    protected fun drawNodeInstanced(node: Node, offset: Long = 0) {
        val s = getOpenGLObjectStateFromNode(node)

        gl.glBindVertexArray(s.mVertexArrayObject[0])

        if (s.mStoredIndexCount > 0) {
            gl.glDrawElementsInstanced(
                (node as HasGeometry).geometryType.toOpenGLType(),
                s.mStoredIndexCount,
                GL4.GL_UNSIGNED_INT,
                offset,
                s.instanceCount)
        } else {
            gl.glDrawArraysInstanced(
                (node as HasGeometry).geometryType.toOpenGLType(),
                0, s.mStoredPrimitiveCount, s.instanceCount)

        }

//        gl.glUseProgram(0)
//        gl.glBindVertexArray(0)
    }

    override fun screenshot(filename: String, overwrite: Boolean) {
        screenshotRequested = true
        screenshotOverwriteExisting = overwrite
        screenshotFilename = filename
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

    private fun Blending.BlendFactor.toOpenGL() = when (this) {
        Blending.BlendFactor.Zero -> GL4.GL_ZERO
        Blending.BlendFactor.One -> GL4.GL_ONE

        Blending.BlendFactor.SrcAlpha -> GL4.GL_SRC_ALPHA
        Blending.BlendFactor.OneMinusSrcAlpha -> GL4.GL_ONE_MINUS_SRC_ALPHA

        Blending.BlendFactor.SrcColor -> GL4.GL_SRC_COLOR
        Blending.BlendFactor.OneMinusSrcColor -> GL4.GL_ONE_MINUS_SRC_COLOR

        Blending.BlendFactor.DstColor -> GL4.GL_DST_COLOR
        Blending.BlendFactor.OneMinusDstColor -> GL4.GL_ONE_MINUS_DST_COLOR

        Blending.BlendFactor.DstAlpha -> GL4.GL_DST_ALPHA
        Blending.BlendFactor.OneMinusDstAlpha -> GL4.GL_ONE_MINUS_DST_ALPHA

        Blending.BlendFactor.ConstantColor -> GL4.GL_CONSTANT_COLOR
        Blending.BlendFactor.OneMinusConstantColor -> GL4.GL_ONE_MINUS_CONSTANT_COLOR

        Blending.BlendFactor.ConstantAlpha -> GL4.GL_CONSTANT_ALPHA
        Blending.BlendFactor.OneMinusConstantAlpha -> GL4.GL_ONE_MINUS_CONSTANT_ALPHA

        Blending.BlendFactor.Src1Color -> GL4.GL_SRC1_COLOR
        Blending.BlendFactor.OneMinusSrc1Color -> GL4.GL_ONE_MINUS_SRC1_COLOR

        Blending.BlendFactor.Src1Alpha -> GL4.GL_SRC1_ALPHA
        Blending.BlendFactor.OneMinusSrc1Alpha -> GL4.GL_ONE_MINUS_SRC1_ALPHA

        Blending.BlendFactor.SrcAlphaSaturate -> GL4.GL_SRC_ALPHA_SATURATE
    }

    private fun Blending.BlendOp.toOpenGL() = when (this) {
        Blending.BlendOp.add -> GL4.GL_FUNC_ADD
        Blending.BlendOp.subtract -> GL4.GL_FUNC_SUBTRACT
        Blending.BlendOp.min -> GL4.GL_MIN
        Blending.BlendOp.max -> GL4.GL_MAX
        Blending.BlendOp.reverse_subtract -> GL4.GL_FUNC_REVERSE_SUBTRACT
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
                    @Suppress("UNCHECKED_CAST")
                    val shaders = setting.value as? List<String> ?: return@forEach

                    renderConfig.renderpasses[pass]?.shaders = shaders

                    mustRecreateFramebuffers = true
                    framebufferRecreateHook = {
                        renderConfig.qualitySettings[quality]?.filter { !it.key.endsWith(".shaders") }?.forEach {
                            setConfigSetting(it.key, it.value)
                        }

                        framebufferRecreateHook = {}
                    }
                } else {
                    setConfigSetting(setting.key, setting.value)
                }
            }
        } else {
            logger.warn("The current renderer config, $renderConfigFile, does not support setting quality options.")
        }
    }

    /**
     * Closes this renderer instance.
     */
    override fun close() {
        if (shouldClose || !initialized) {
            return
        }

        shouldClose = true

        lastResizeTimer.cancel()
        encoder?.finish()

        cglWindow?.closeNoEDT()
        joglDrawable?.destroy()
    }
}
