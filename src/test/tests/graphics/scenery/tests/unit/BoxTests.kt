package graphics.scenery.tests.unit

import graphics.scenery.mesh.Box
import graphics.scenery.Scene
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [Box] primitive.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class BoxTests {
    private val logger by LazyLogger()

    @Test
    fun testCreation() {
        logger.info("Testing Box creating and bounding box calculation ...")
        val s = Scene()
        val size = Random.random3DVectorFromRange(0.1f, 500.0f)
        val epsilon = 0.0001f
        val b = Box(size)
        s.addChild(b)

        val bb = b.boundingBox
        assertNotNull(bb)
        assertTrue(bb.min.x() >= -size.x() / 2.0f - epsilon)
        assertTrue(bb.min.y() >= -size.y() / 2.0f - epsilon)
        assertTrue(bb.min.z() >= -size.z() / 2.0f - epsilon)

        assertTrue(bb.max.x() <= size.x() / 2.0f + epsilon)
        assertTrue(bb.max.y() <= size.y() / 2.0f + epsilon)
        assertTrue(bb.max.z() <= size.z() / 2.0f + epsilon)
    }
}
