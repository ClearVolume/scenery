package graphics.scenery.tests.unit.controls.behaviours

import org.joml.Vector3f
import graphics.scenery.Camera
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.Hub
import graphics.scenery.Scene
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.controls.behaviours.ArcballCameraControl
import graphics.scenery.tests.unit.backends.FauxRenderer
import graphics.scenery.utils.LazyLogger
import org.joml.Quaternionf
import org.junit.Assert
import org.junit.Test
import java.io.FileWriter
import java.io.PrintWriter
import java.util.zip.GZIPInputStream
import kotlin.test.assertNotNull


/**
 * Tests for [ArcballCameraControl]
 *
 * @author Aryaman Gupta <aryaman1994@gmail.com>
 */
class ArcballCameraControlTests {
    private val logger by LazyLogger()
    private val seqLength = 100

    private fun prepareArcballCameraControl(scene: Scene) : ArcballCameraControl {
        val hub: Hub = Hub()

        val renderer = FauxRenderer(hub, scene)
        hub.add(renderer)

        val target = scene.findObserver()?.target ?: Vector3f(0.0f)

        val arcballCameraControl = ArcballCameraControl("TestController", { scene.findObserver() }, 512, 512, target)
        return arcballCameraControl
    }

    private fun prepareTestFile() {
        val scene = Scene()

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial().position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)
            scene.addChild(this)
        }

        val arcballCameraControl = prepareArcballCameraControl(scene)

        val movementRange = 32
        val path = "src/test/resources/graphics/scenery/tests/unit/controls/behaviours/arcballSequence.json"
        val fileWriter = FileWriter(path, true)
        val printWriter = PrintWriter(fileWriter)

        val initX = kotlin.random.Random.nextInt(0, 512)
        val initY = kotlin.random.Random.nextInt(0, 512)

        var x = initX
        var y = initY

        arcballCameraControl.init(initX, initY)

        /*TODO: Generate multiple test sequences*/
        for(i in 1..seqLength) {
            cam.spatial {
                printWriter.println("[$i, $x, $y, ${rotation.x}, ${rotation.y}, ${rotation.z}, ${rotation.w}, ${position.x()}, ${position.y()}, ${position.z()}]")
            }
            val deltaX = kotlin.random.Random.nextInt(-movementRange, movementRange)
            val deltaY = kotlin.random.Random.nextInt(-movementRange, movementRange)

            x = (x + deltaX).coerceIn(0..511)
            y = (y + deltaY).coerceIn(0..511)

            arcballCameraControl.drag(x, y)
        }
        arcballCameraControl.end(x, y)
        printWriter.close()
    }

    /**
     * Tests initialisation of [ArcballCameraControl].
     */
    @Test
    fun testInitialisation() {
        logger.info("Testing ArcballCameraControl initialisation...")
        val scene = Scene()
        val arcballCameraControl = prepareArcballCameraControl(scene)
        assertNotNull(arcballCameraControl)
    }

    /**
     * Tests arcball interaction with a set of pre-created samples.
     */
    @Test
    fun testArcballControl() {
        logger.info("Testing ArcballCameraControl...")
        val scene = Scene()

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial().position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)
            scene.addChild(this)
        }

        val arcballCameraControl = prepareArcballCameraControl(scene)

        val samplesStream = GZIPInputStream(ArcballCameraControlTests::class.java.getResourceAsStream("arcballSequence.json.gz"))
        samplesStream.reader().readLines().forEach { line ->
            val numbers = line.replace("[", "").replace("]", "").split(",").map { it.toFloat() }
            if(numbers[0].toInt() == 1) {
                arcballCameraControl.init(numbers[1].toInt(), numbers[2].toInt())
            }
            else {
                arcballCameraControl.drag(numbers[1].toInt(), numbers[2].toInt())
            }

            if(numbers[0].toInt() == seqLength) {
                arcballCameraControl.end(numbers[1].toInt(), numbers[2].toInt())
            }

            val expectedRotation = Quaternionf(numbers[3], numbers[4], numbers[5], numbers[6])
            val expectedPosition = Vector3f(numbers[7], numbers[8], numbers[9])

            Assert.assertEquals("Computed camera rotation is incorrect", expectedRotation, cam.spatial().rotation)
            Assert.assertEquals("Computed camera position is incorrect", expectedPosition, cam.spatial().position)
        }
    }
}
