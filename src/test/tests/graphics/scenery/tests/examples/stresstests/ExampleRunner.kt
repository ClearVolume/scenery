package graphics.scenery.tests.examples.stresstests

import graphics.scenery.SceneryBase
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.utils.ExtractsNatives
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import org.reflections.Reflections
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Experimental test runner that saves screenshots of all discovered tests.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class ExampleRunner {
    val logger by LazyLogger()

    @Test
    fun runAllExamples() {
        val reflections = Reflections("graphics.scenery.tests")

        // blacklist contains examples that require user interaction or additional devices
        val blacklist = listOf("LocalisationExample",
            "TexturedCubeJavaApplication",
            "JavaFXTexturedCubeApplication",
            "JavaFXTexturedCubeExample",
            "JavaFXGridPaneExample",
            "XwingLiverExample",
            "VRControllerExample",
            "EyeTrackingExample",
            "ARExample",
            "ReaderExample",
            "BDVExample")

        // find all basic and advanced examples, exclude blacklist
        val examples = reflections
            .getSubTypesOf(SceneryBase::class.java)
            .filter { !it.canonicalName.contains("stresstests") && !it.canonicalName.contains("cluster") }
            .filter { !blacklist.contains(it.simpleName) }

        val renderers = when (ExtractsNatives.getPlatform()) {
            ExtractsNatives.Platform.WINDOWS -> listOf("VulkanRenderer", "OpenGLRenderer")
            ExtractsNatives.Platform.LINUX -> listOf("VulkanRenderer", "OpenGLRenderer")
            ExtractsNatives.Platform.MACOS -> listOf("OpenGLRenderer")
            ExtractsNatives.Platform.UNKNOWN -> {
                logger.error("Don't know what to do on this platform, sorry."); return
            }
        }

        val directoryName = "ExampleRunner-${SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(Date())}"
        Files.createDirectory(Paths.get(directoryName))


        renderers.forEach { renderer ->
            System.setProperty("scenery.Renderer", renderer)
            val executor = Executors.newSingleThreadExecutor()
            val rendererDirectory = "$directoryName/$renderer"
            Files.createDirectory(Paths.get(rendererDirectory))

            examples.shuffled().forEachIndexed { i, example ->
                logger.info("Running ${example.simpleName} with $renderer ($i/${examples.size}) ...")

                if (!example.simpleName.contains("JavaFX")) {
                    System.setProperty("scenery.Headless", "true")
                }

                val instance = example.getConstructor().newInstance()
                val future: Future<*>

                try {
                    val exampleRunnable = Runnable {
                        instance.main()
                    }

                    future = executor.submit(exampleRunnable)

                    while (!instance.running || !instance.sceneInitialized()) {
                        Thread.sleep(200)
                    }

                    Thread.sleep(2000)
                    (instance.hub.get(SceneryElement.Renderer) as Renderer).screenshot("$rendererDirectory/${example.simpleName}.png")
                    Thread.sleep(2000)

                    logger.info("Sending close to ${example.simpleName}")
                    instance.close()

                    while (instance.running) {
                        Thread.sleep(200)
                    }

                    future.get()
                } catch (e: ThreadDeath) {
                    logger.info("JOGL threw ThreadDeath")
                }

                logger.info("${example.simpleName} closed ($renderer ran ${i + 1}/${examples.size} so far).")

                Thread.sleep(5000)
            }
        }
    }
}
