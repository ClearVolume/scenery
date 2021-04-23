package graphics.scenery.backends

import com.jogamp.opengl.GLAutoDrawable
import graphics.scenery.*
import graphics.scenery.backends.opengl.OpenGLRenderer
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.textures.Texture
import graphics.scenery.utils.ExtractsNatives
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.SceneryPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renderer interface. Defines the minimal set of functions a renderer has to implement.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
@Suppress("unused")
abstract class Renderer : Hubable {
    /**
     * Initializes scene and contents
     */
    abstract fun initializeScene()

    /**
     * Renders the scene
     */
    abstract fun render(activeCamera: Camera, sceneNodes: List<Node>)

    /** Signals whether the current renderer should stop working and close all open windows. */
    abstract var shouldClose: Boolean

    /** Signals whether the renderer is done initialiasing and can start with scene initialisation and rendering. */
    abstract var initialized: Boolean
        protected set

    /** Signals whether a first image has been drawn. */
    abstract var firstImageReady: Boolean
        protected set

    /** The total number of frames rendered so far. */
    var totalFrames = 0L
        protected set

    /** [Settings] instance the renderer is using. */
    abstract var settings: Settings

    /** [SceneryWindow] the renderer is drawing to. */
    abstract var window: SceneryWindow

    /** A [SceneryPanel] the renderer might be embedded in. */
    abstract var embedIn: SceneryPanel?

    /**
     * Method to close the renderer.
     */
    abstract fun close()

    /**
     * Takes a screenshot, and saves it to the users's desktop directory.
     */
    fun screenshot() {
        screenshot("")
    }

    /**
     * Takes a screenshot, and saves it as [filename].
     *
     * @param[filename] The filename where to save the screenshot.
     */
    abstract fun screenshot(filename: String = "", overwrite: Boolean = false)

    /**
     * Records a movie with the default filename.
     */
    fun recordMovie() {
        recordMovie("")
    }

    /**
     * Starts recording a movie, and saves it as [filename].
     *
     * @param[filename] The filename where to save the screenshot.
     */
    abstract fun recordMovie(filename: String = "", overwrite: Boolean = false)

    /**
     * Reshapes the window to the given sizes.
     *
     * @param[newWidth] The new width of the window.
     * @param[newHeight] The new height of the window.
     */
    abstract fun reshape(newWidth: Int, newHeight: Int)

    /**
     * Sets the rendering quality, if the loaded renderer config file supports it.
     *
     * @param[quality] The [RenderConfigReader.RenderingQuality] to be set.
     */
    abstract fun setRenderingQuality(quality: RenderConfigReader.RenderingQuality)

    /**
     * Activate or deactivate push-based rendering mode (render only on scene changes
     * or input events). Push mode is activated if [pushMode] is true.
     */
    abstract var pushMode: Boolean

    /**
     * Whether the renderer manages it's own main loop. If false, [graphics.scenery.SceneryBase] will take
     * care of the rendering loop inside its main loop.
     */
    abstract val managesRenderLoop: Boolean

    /** Total time taken for the last frame (in milliseconds). */
    abstract var lastFrameTime: Float

    /** The file to read the [RenderConfigReader.RenderConfig] from. */
    abstract var renderConfigFile: String

    /**
     * Toggles VR on and off, and loads the appropriate renderer config file, if it exists.
     * The name is the name of the current renderer config file, with "Stereo" at the end.
     */
    @Suppress("UNUSED")
    fun toggleVR() {
        val logger by LazyLogger()
        logger.info("Toggling VR!")
        val isStereo = renderConfigFile.substringBeforeLast(".").indexOf("Stereo") != -1

        if (isStereo) {
            val nonStereoConfig = renderConfigFile.substringBeforeLast("Stereo") + ".yml"

            if (RenderConfigReader::class.java.getResource(nonStereoConfig) != null) {
                renderConfigFile = nonStereoConfig
                settings.set("vr.Active", false)
            } else {
                logger.warn("Non-stereo configuration for $renderConfigFile ($nonStereoConfig) not found.")
            }
        } else {
            val stereoConfig = renderConfigFile.substringBeforeLast(".") + "Stereo.yml"

            if (RenderConfigReader::class.java.getResource(stereoConfig) != null) {
                renderConfigFile = stereoConfig
                settings.set("vr.Active", true)
            } else {
                logger.warn("Stereo VR configuration for $renderConfigFile ($stereoConfig) not found.")
            }
        }
    }

    /**
     * Adds the default [Settings] for [Renderer] to a given [Settings] instance.
     *
     * Providing some sane defaults that may of course be overridden after
     * construction of the renderer.
     *
     * @param[settings] The [Settings] instance to augment.
     * @return Default [Settings] values.
     */
    fun loadDefaultRendererSettings(settings: Settings): Settings {
        settings.setIfUnset("wantsFullscreen", false)
        settings.setIfUnset("isFullscreen", false)

        settings.setIfUnset("vr.Active", false)
        settings.setIfUnset("vr.IPD", 0.05f)

        settings.setIfUnset("sdf.MaxDistance", 12)

        settings.setIfUnset("Renderer.PrintGPUStats", false)
        settings.setIfUnset("Renderer.SupersamplingFactor", System.getProperty("scenery.Renderer.SupersamplingFactor")?.toFloat()
            ?: 1.0f)
        settings.setIfUnset("Renderer.DisableVsync", false)
        settings.setIfUnset("Renderer.ForceUndecoratedWindow", false)

        return settings
    }

    @Volatile protected var textureRequests = ConcurrentLinkedQueue<Pair<Texture, Channel<Texture>>>()

    /**
     * A list of user-defined lambdas that will be executed once per iteration of the render loop
     */
    val postRenderLambdas = ArrayList<()->Unit>()

    /**
     * Requests the renderer to update [texture]'s contents from the GPU. [onReceive] is executed
     * on receiving the data.
     */
    fun requestTexture(texture: Texture, onReceive: (Texture) -> Unit): Deferred<Unit> = GlobalScope.async {
        val channel = Channel<Texture>(capacity = Channel.CONFLATED)
        textureRequests.add(texture to channel)

        select<Unit> {
            channel.onReceive { onReceive.invoke(it) }
        }
    }

    @Volatile protected var imageRequests = ConcurrentLinkedQueue<RenderedImage>()

    /**
     * Requests a screenshot from the renderer, stored as [RenderedImage].
     */
    fun requestScreenshot(): RenderedImage  = runBlocking {
        val reactivatePushMode = if(pushMode) {
            pushMode = false
            true
        } else {
            false
        }

        val screenshot = GlobalScope.async {
            val s = RenderedImage.RenderedRGBAImage(0, 0, null)
            imageRequests.offer(s)

            while(s.data == null) {
                delay(10)
            }

            s
        }

        val result = screenshot.await()
        if(reactivatePushMode) {
            pushMode = true
        }

        result
    }

    /**
     * Factory methods for creating renderers.
     */
    companion object {
        val logger by LazyLogger()

        /**
         * Creates a new [Renderer] instance, based on what is available on the current platform, or set via
         * the scenery.Renderer system property.
         *
         * On Linux and Windows, [VulkanRenderer]will be created by default.
         * On macOS, [OpenGLRenderer] will be created by default.
         *
         * @param[hub] The [Hub] to use.
         * @param[applicationName] Application name, mainly used for the title bar if shown.
         * @param[scene] The initial [Scene] the renderer should display.
         * @param[windowWidth] Window width for the renderer window.
         * @param[windowHeight] Window height for the renderer window.
         * @param[embedIn] A [SceneryWindow] to embed the renderer in, can e.g. be a Swing or GLFW window.
         * @param[embedInDrawable] A [GLAutoDrawable] to embed the renderer in. [embedIn] and [embedInDrawable] are mutually exclusive.
         * @param[renderConfigFile] A YAML file with the render path configuration from which a [RenderConfigReader.RenderConfig] will be created.
         *
         * @return A new [Renderer] instance.
         */
        @JvmOverloads
        @JvmStatic
        fun createRenderer(hub: Hub, applicationName: String, scene: Scene, windowWidth: Int, windowHeight: Int, embedIn: SceneryPanel? = null, embedInDrawable: GLAutoDrawable? = null, renderConfigFile: String? = null): Renderer {
            var preference = System.getProperty("scenery.Renderer", null)
            val config = renderConfigFile ?: System.getProperty("scenery.Renderer.Config", "DeferredShading.yml")

            preference = when {
                preference == null &&
                    (ExtractsNatives.getPlatform() == ExtractsNatives.Platform.LINUX
                        || ExtractsNatives.getPlatform() == ExtractsNatives.Platform.WINDOWS) -> "VulkanRenderer"

                preference == null &&
                    ExtractsNatives.getPlatform() == ExtractsNatives.Platform.MACOS -> "OpenGLRenderer"

                else -> preference
            }

            return try {
                if (preference == "VulkanRenderer" && embedInDrawable == null) {
                    try {
                        VulkanRenderer(hub, applicationName, scene, windowWidth, windowHeight, embedIn, config)
                    } catch (e: RendererUnavailableException) {
                        logger.warn("Vulkan unavailable ($e, ${e.cause}, ${e.message}), falling back to OpenGL.")
                        logger.debug("Full exception: $e")
                        if(logger.isDebugEnabled || System.getenv("CI") != null) {
                            e.printStackTrace()
                        }
                        OpenGLRenderer(hub, applicationName, scene, windowWidth, windowHeight, config, embedIn, embedInDrawable)
                    } catch (e: UnsatisfiedLinkError) {
                        logger.warn("Vulkan unavailable (${e.cause}, ${e.message}), Vulkan runtime not installed. Falling back to OpenGL.")
                        logger.debug("Full exception: $e")
                        if(logger.isDebugEnabled || System.getenv("CI") != null) {
                            e.printStackTrace()
                        }
                        OpenGLRenderer(hub, applicationName, scene, windowWidth, windowHeight, config, embedIn, embedInDrawable)
                    }
                } else {
                    OpenGLRenderer(hub, applicationName, scene, windowWidth, windowHeight, config, embedIn, embedInDrawable)
                }
            } catch (e: Exception) {
                logger.error("Could not instantiate renderer. Is your graphics card working properly and do you have the most recent drivers installed?")
                e.printStackTrace()
                throw e
            }
        }
    }
}
