package graphics.scenery.tests.unit

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.TextBoard
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the [Camera] class.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class CameraTests {
    private val logger by LazyLogger()

    /**
     * Tests [Camera.showMessage] by showing a message, expecting
     * that to become part of the scene graph, and being removed
     * from it again after a given duration.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun testShowMessage() {
        val duration = 400
        val s = Scene()
        val cam = Camera()
        s.addChild(cam)

        cam.showMessage("hello camera", duration = duration)
        assertTrue("Scene contains messages TextBoard from Camera") { (cam.children.last() as? TextBoard)?.text == "hello camera" }
        assertTrue("Camera contains messages metadata object") { cam.metadata.containsKey("messages") }
        Thread.sleep(2L * duration)

        assertTrue("TextBoard was removed from scene after showing duration") { cam.children.size == 0 }
        assertTrue("Messages metadata object contains no more entries") { (cam.metadata["messages"] as MutableList<Node>).isEmpty() }
    }

    /**
     * Tests [Camera.canSee] by adding nodes in front and behind the
     * camera, and checking whether they are visible from the camera's viewpoint.
     */
    @Test
    fun testCanSee() {
        val s = Scene()
        val cam = Camera()
        cam.perspectiveCamera(50.0f, 1280, 720, 0.01f, 1000.0f)
        s.addChild(cam)

        val boxesInFront = (0 until 10).map {
            val b = Box()
            b.position = Vector3f(0.0f,
                Random.randomFromRange(-0.5f, 0.5f),
                Random.randomFromRange(2.0f, 10.0f))
            s.addChild(b)
            b
        }

        val boxesBehind = (0 until 10).map {
            val b = Box()
            b.position = Random.random3DVectorFromRange(-0.5f, -10.0f)
            s.addChild(b)
            b
        }

        s.updateWorld(true, true)

        assertTrue { boxesInFront.all { cam.canSee(it) } }
        assertFalse { boxesBehind.all { cam.canSee(it) } }
    }
}
