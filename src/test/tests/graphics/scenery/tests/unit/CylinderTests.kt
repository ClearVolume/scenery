package graphics.scenery.tests.unit

import graphics.scenery.mesh.Cylinder
import graphics.scenery.Scene
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [Cylinder] primitive.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class CylinderTests {
    private val logger by LazyLogger()

    @Test
    fun testCreation() {
        logger.info("Testing Cylinder creating and bounding box calculation ...")
        val s = Scene()
        val radius = Random.randomFromRange(0.1f, 100.0f)
        val height = Random.randomFromRange(0.1f, 100.0f)
        val segments = kotlin.random.Random.nextInt(2, 20)
        val epsilon = 0.0001f

        val b = Cylinder(radius, height, segments)
        s.addChild(b)

        val bb = b.boundingBox
        assertNotNull(bb)
        assertTrue(bb.min.x() >= -radius - epsilon)
        assertTrue(bb.min.y() >= 0.0f - epsilon)
        assertTrue(bb.min.z() >= -radius - epsilon)

        assertTrue(bb.max.x() <= radius + epsilon)
        assertTrue(bb.max.y() <= height + epsilon)
        assertTrue(bb.max.z() <= radius + epsilon)
    }
}
