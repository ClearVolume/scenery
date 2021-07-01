package graphics.scenery.tests.examples.stresstests

import graphics.scenery.SceneryBase
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.utils.ExtractsNatives
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.SystemHelpers
import kotlinx.coroutines.*
import org.junit.Test
import org.reflections.Reflections
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.test.assertFalse
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.minutes

/**
 * Experimental test runner that saves screenshots of all discovered tests.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
@ExperimentalCoroutinesApi
@ExperimentalTime
class ExampleRunner {
    val logger by LazyLogger()

    @Volatile private var failure = false

    private var maxRuntimePerTest = System.getProperty("scenery.ExampleRunner.maxRuntimePerTest", "5").toInt().minutes
    @Volatile private var runtime = 0.milliseconds

    /**
     * Runs all examples in the class path and bails out on the first exception encountered.
     * Examples are run using both renderers and stereo and non-stereo render paths. Examples
     * are run in random order.
     */
    @Test fun runAllExamples() = runBlocking {
        val reflections = Reflections("graphics.scenery.tests")

        // blacklist contains examples that require user interaction or additional devices
        val blocklist = mutableListOf(
            "LocalisationExample",
            "SwingTexturedCubeExample",
            "TexturedCubeJavaExample",
            "XwingLiverExample",
            "VRControllerExample",
            "EyeTrackingExample",
            "ARExample",
            "ReaderExample",
            "BDVExample",
            "BigAndSmallVolumeExample",
            "VolumeSamplingExample",
            "SwingTexturedCubeExample",
            "VideoRecordingExample",
            // TODO: this has a bug apparently coming from BVV
            "FlybrainOutOfCoreExample"
        )

        blocklist.addAll(System.getProperty("scenery.ExampleRunner.Blocklist", "").split(","))
        val allowedTests = System.getProperty("scenery.ExampleRunner.AllowedTests")?.split(",")

        // find all basic and advanced examples, exclude blacklist
        val examples = reflections
            .getSubTypesOf(SceneryBase::class.java)
            .filter { !it.canonicalName.contains("stresstests") && !it.canonicalName.contains("cluster") }
            .filter { !blocklist.contains(it.simpleName) }.toMutableList()
            .filter { allowedTests?.contains(it.simpleName) ?: true }

        val rendererProperty = System.getProperty("scenery.Renderer")
        val renderers = rendererProperty?.split(",") ?: when(ExtractsNatives.getPlatform()) {
            ExtractsNatives.Platform.WINDOWS,
            ExtractsNatives.Platform.LINUX -> listOf("VulkanRenderer", "OpenGLRenderer")
            ExtractsNatives.Platform.MACOS -> listOf("OpenGLRenderer")
            ExtractsNatives.Platform.UNKNOWN -> {
                logger.error("Don't know what to do on this platform, sorry."); return@runBlocking
            }
        }

        val configurations = listOf("DeferredShading.yml", "DeferredShadingStereo.yml")

        val directoryName = System.getProperty("scenery.ExampleRunner.OutputDir") ?: "ExampleRunner-${SystemHelpers.formatDateTime(delimiter = "_")}"
        Files.createDirectory(Paths.get(directoryName))

        logger.info("ExampleRunner: Running ${examples.size} examples with ${configurations.size} configurations. Memory: ${Runtime.getRuntime().freeMemory().toFloat()/1024.0f/1024.0f}M/${Runtime.getRuntime().totalMemory().toFloat()/1024.0f/1024.0f}/${Runtime.getRuntime().maxMemory().toFloat()/1024.0f/1024.0f}M (free/total/max) available.")
        System.setProperty("scenery.RandomSeed", "31337")

        renderers.shuffled().forEach { renderer ->
            System.setProperty("scenery.Renderer", renderer)

            configurations.shuffled().forEach { config ->
                System.setProperty("scenery.Renderer.Config", config)

                val rendererDirectory = "$directoryName/$renderer-${config.substringBefore(".")}"
                Files.createDirectory(Paths.get(rendererDirectory))

                examples.shuffled().forEachIndexed { i, example ->
                    runtime = 0.milliseconds

                    logger.info("Running ${example.simpleName} with $renderer ($i/${examples.size}) ...")
                    logger.info("Memory: ${Runtime.getRuntime().freeMemory().toFloat()/1024.0f/1024.0f}M/${Runtime.getRuntime().totalMemory().toFloat()/1024.0f/1024.0f}/${Runtime.getRuntime().maxMemory().toFloat()/1024.0f/1024.0f}M (free/total/max) available.")

                    if (!example.simpleName.contains("JavaFX")) {
                        System.setProperty("scenery.Headless", "true")
                    }

                    val instance = example.getConstructor().newInstance()
                    var exampleRunnable: Job? = null

                    try {
                        val handler = CoroutineExceptionHandler { _, e ->
                            logger.error("${example.simpleName}: Received exception $e")
                            logger.error("Stack trace: ${e.stackTraceToString()}")

                            failure = true
                            // we fail very hard here to prevent process clogging the CI
                            exitProcess(-1)
                        }

                        exampleRunnable = GlobalScope.launch(handler) {
                            instance.assertions[SceneryBase.AssertionCheckPoint.BeforeStart]?.forEach {
                                it.invoke()
                            }
                            instance.main()
                        }

                        while (!instance.running || !instance.sceneInitialized() || instance.hub.get(SceneryElement.Renderer) == null) {
                            delay(200)
                        }
                        val r = (instance.hub.get(SceneryElement.Renderer) as Renderer)

                        while(!r.firstImageReady) {
                            delay(200)
                        }

                        r.screenshot("$rendererDirectory/${example.simpleName}.png")
                        Thread.sleep(2000)

                        logger.info("Sending close to ${example.simpleName}")
                        instance.close()
                        instance.assertions[SceneryBase.AssertionCheckPoint.AfterClose]?.forEach {
                            it.invoke()
                        }

                        while (instance.running && !failure) {
                            if (runtime > maxRuntimePerTest) {
                                exampleRunnable.cancelAndJoin()
                                logger.error("Maximum runtime of $maxRuntimePerTest exceeded, aborting test run.")
                                failure = true
                            }

                            runtime += 200.milliseconds
                            delay(200)
                        }

                        if(failure) {
                            exampleRunnable.cancelAndJoin()
                        } else {
                            exampleRunnable.join()
                        }
                    } catch (e: ThreadDeath) {
                        logger.info("JOGL threw ThreadDeath")
                    }

                    logger.info("${example.simpleName} closed ($renderer ran ${i + 1}/${examples.size} so far).")

                    assertFalse(failure, "ExampleRunner aborted due to exceptions in tests or exceeding maximum per-test runtime of $maxRuntimePerTest.")
                }
            }
        }
    }
}
