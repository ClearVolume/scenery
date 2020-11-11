package graphics.scenery.tests.unit

import org.joml.Vector3f
import graphics.scenery.mesh.Box
import graphics.scenery.Node
import graphics.scenery.Scene
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.test.assertEquals

/**
 * Tests for [Scene].
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class SceneTests {
    private val logger by LazyLogger()

    @Test
    fun testRaycast() {
        val count = Random.randomFromRange(15.0f, 100.0f).roundToInt()
        logger.info("Testing raycast in scene with $count objects ...")

        val scene = Scene()
        (0 until count).map {
            Box()
        }.forEachIndexed { i, n ->
            n.position = Vector3f(
                Random.randomFromRange(-0.4f, 0.4f),
                Random.randomFromRange(-0.4f, 0.4f),
                -1.0f + i * (-1.5f))

            scene.addChild(n)
        }
        scene.updateWorld(true)

        val results = scene.raycast(
            position = Vector3f(0.0f),
            direction = Vector3f(0.0f, 0.0f, -1.0f),
            ignoredObjects = emptyList())

        assertEquals(count, results.matches.size, "Raycast should have hit $count objects")
    }

    @Test
    fun testEventHandlers() {
        logger.info("Testing Scene event handlers ...")
        val scene = Scene()
        val nodesAdded = AtomicInteger(0)
        val nodesDeleted = AtomicInteger(0)

        scene.onChildrenAdded["adder"] = { _, _ ->
            nodesAdded.incrementAndGet()
        }

        scene.onChildrenRemoved["remover"] = { _, _ ->
            nodesDeleted.incrementAndGet()
        }
        val nodeCount = Random.randomFromRange(2.0f, 10.0f).roundToInt()
        val nodes = (0 until nodeCount).map {
            Node()
        }

        nodes.forEach { scene.addChild(it) }
        nodes.forEach { scene.removeChild(it) }

        Thread.sleep(200)
        assertEquals(nodeCount, nodesAdded.get())
        assertEquals(nodeCount, nodesDeleted.get())
    }
}
