package graphics.scenery.tests.unit

import graphics.scenery.mesh.Icosphere
import graphics.scenery.Scene
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Class to test the [Icosphere] primitive.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class IcosphereTests {
    private val logger by LazyLogger()

    @Test
    fun testCreation() {
        logger.info("Testing Icosphere creating and bounding box calculation ...")
        val s = Scene()
        val radius = Random.randomFromRange(0.1f, 100.0f)
        val subdivisions = kotlin.random.Random.nextInt(1, 3)
        val epsilon = 0.0001f

        val i = Icosphere(radius, subdivisions)
        s.addChild(i)

        val bb = i.boundingBox
        assertNotNull(bb)
        assertTrue(bb.min.x() >= -radius - epsilon)
        assertTrue(bb.min.y() >= -radius - epsilon)
        assertTrue(bb.min.z() >= -radius - epsilon)

        assertTrue(bb.max.x() <= radius + epsilon)
        assertTrue(bb.max.y() <= radius + epsilon)
        assertTrue(bb.max.z() <= radius + epsilon)
    }
}
