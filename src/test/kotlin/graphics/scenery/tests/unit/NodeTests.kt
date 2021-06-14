package graphics.scenery.tests.unit

import org.joml.Matrix4f
import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.compare
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.toFloatArray
import net.imglib2.RealPoint
import org.joml.Quaternionf
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for functions of [Node]
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class NodeTests {
    private val logger by LazyLogger()

    /**
     * Tests matrix propagation through the scene graph.
     */
    @Test
    fun testTransformationPropagation() {
        val scene = Scene()

        val childOne = RichNode("first child")
        val subChild = RichNode("child of first child")

        scene.addChild(childOne)
        childOne.addChild(subChild)

        childOne.spatial {
            position = Vector3f(1.0f, 1.0f, 1.0f)
        }
        subChild.spatial {
            position = Vector3f(-1.0f, -1.0f, -1.0f)
            scale = Vector3f(2.0f, 1.0f, 1.0f)
            rotation = Quaternionf().rotateXYZ(0.5f, 0.5f, 0.5f)
        }

        logger.info("childOne:\n${childOne.spatial().world}")
        logger.info("subChild:\n${subChild.spatial().world}")

        childOne.spatial().updateWorld(true, force = false)
        //subChild.updateWorld(true, force = false)

        logger.info("\n-------\nAfter update:\n")

        logger.info("childOne:\n${childOne.spatial().world}")
        logger.info("subChild:\n${subChild.spatial().world}")

        val expectedResult = Matrix4f().identity()
        expectedResult.translate(1.0f, 1.0f, 1.0f)
        expectedResult.translate(-1.0f, -1.0f, -1.0f)
        expectedResult.rotateXYZ(0.5f, 0.5f, 0.5f)
        expectedResult.scale(2.0f, 1.0f, 1.0f)

        logger.info(expectedResult.toString())

        assertTrue(expectedResult.compare(subChild.spatial().world, true), "Expected transforms to be equal")
    }

    @Test
    fun originParentPositionTransformation(){
        val scene = Scene()

        val parent = RichNode()
        scene.addChild(parent)
        val child = RichNode()
        parent.addChild(child)
        child.spatial {
            position += Vector3f(0.5f)
            updateWorld(true,false)
        }

        assertEquals(Vector3f(), parent.spatial().position)
        assertEquals(Vector3f(), parent.spatial().worldPosition())
        assertEquals(Vector3f(0.5f), child.spatial().position)
        assertEquals(Vector3f(0.5f), child.spatial().worldPosition(Vector3f(0f)))
    }

    @Test
    fun nonOriginParentPositionTransformation(){
        val scene = Scene()

        val parent = RichNode()
        parent.spatial().position += Vector3f(0.5f)
        scene.addChild(parent)
        val child = RichNode()
        parent.addChild(child)
        child.spatial().position += Vector3f(0.5f)
        parent.spatial().updateWorld(true, false)

        assertEquals(Vector3f(0.5f), parent.spatial().position)
        assertEquals(Vector3f(0.5f), parent.spatial().worldPosition())
        assertEquals(Vector3f(0.5f), child.spatial().position)
        assertEquals(Vector3f(1f), child.spatial().worldPosition(Vector3f(0f)))
    }

    @Test
    fun scaledParentPositionTransformation(){
        val scene = Scene()

        val parent = RichNode()
        //parent.position += Vector3f(0.5f)
        parent.spatial().scale = Vector3f(0.5f)
        scene.addChild(parent)
        val child = RichNode()
        parent.addChild(child)
        child.spatial().position += Vector3f(1f)
        parent.spatial().updateWorld(true, false)

        assertEquals(Vector3f(0f),parent.spatial().position)
        assertEquals(Vector3f(0f),parent.spatial().worldPosition())
        assertEquals(Vector3f(1f),child.spatial().position)
        assertEquals(Vector3f(0.5f),child.spatial().worldPosition(Vector3f(0f)))
    }

    private fun addSiblings(toNode: Node, maxSiblings: Int, currentLevel: Int, maxLevels: Int): Int {
        var totalNodes = 0
        val numSib = ThreadLocalRandom.current().nextInt(1, maxSiblings)

        (0 until numSib).map {
            if(currentLevel >= maxLevels) {
                return totalNodes
            }

            val n = RichNode("Sibling#$it/$currentLevel/$maxLevels")
            n.spatial {
                position = Random.random3DVectorFromRange(-100.0f, 100.0f)
                scale = Random.random3DVectorFromRange(0.1f, 10.0f)
                rotation = Random.randomQuaternion()
            }

            toNode.addChild(n)
            totalNodes++

            totalNodes += addSiblings(n, maxSiblings, currentLevel + 1, maxLevels)
        }

        return totalNodes
    }

    /**
     * Generates a large scene graph which should update fast and not run
     * into an overflow, while staying within a bound for the number of Nodes created.
     */
    @Test
    fun testLargeScenegraph() {
        val scene = Scene()
        val levels = 6
        val maxSiblings = 8

        val totalNodes = addSiblings(scene, maxSiblings, 0, levels)

        logger.info("Created $totalNodes nodes")

        val start = System.nanoTime()
        scene.spatial().updateWorld(true, true)
        val duration = (System.nanoTime() - start)/10e6

        assertTrue(totalNodes <= Math.pow(1.0*maxSiblings, 1.0*levels).toInt(), "Expected total nodes to be less than maximum allowed number")
        assertTrue(totalNodes > maxSiblings, "Expected total nodes to be more than maximum sibling count")

        logger.info("Updating world for $totalNodes took $duration ms")
    }


    /**
     * Generates a large scene graph which should update fast and not run
     * into an overflow, while staying within a bound for the number of Nodes created.
     */
    @Test
    fun testLargeScenegraphDiscovery() {
        val scene = Scene()
        val levels = 6
        val maxSiblings = 8

        val totalNodes = addSiblings(scene, maxSiblings, 0, levels)

        logger.info("Created $totalNodes nodes")

        var start = System.nanoTime()
        scene.spatial().updateWorld(true, true)
        var duration = (System.nanoTime() - start)/10e6

        assertTrue(totalNodes <= Math.pow(1.0*maxSiblings, 1.0*levels).toInt(), "Expected total nodes to be less than maximum allowed number")
        assertTrue(totalNodes > maxSiblings, "Expected total nodes to be more than maximum sibling count")

        logger.info("Updating world for $totalNodes took $duration ms")

        start = System.nanoTime()
        val discoveredNodes = scene.discover(scene, { node -> node.visible })
        duration = (System.nanoTime() - start)/10e6

        assertEquals(totalNodes, discoveredNodes.size, "$totalNodes nodes created, but only ${discoveredNodes.size} nodes discovered.")

        logger.info("Scene discovery for $totalNodes took $duration ms, discovered ${discoveredNodes.size} nodes")
    }

    /**
     * Tests the generation of bounding box coordinates for Meshes.
     */
    @Test
    fun testBoundingBoxGeneration() {
        val m = Mesh()

        val expectedMin = Vector3f(-1.0f, -2.0f, -3.0f)
        val expectedMax = Vector3f(4.0f, 5.0f, 6.0f)

        m.geometry().vertices = BufferUtils.allocateFloatAndPut(
            floatArrayOf(
                expectedMin[0], expectedMin[1], expectedMin[2],
                expectedMax[0], expectedMax[1], expectedMax[2]))

        val expectedPosition = m.geometry().vertices.position()
        val expectedLimit = m.geometry().vertices.limit()

        assertEquals("Vertex buffer position", expectedPosition, m.geometry().vertices.position())
        assertEquals("Vertex buffer limit", expectedLimit, m.geometry().vertices.limit())

        m.boundingBox = m.generateBoundingBox()

        assertNotNull("Bounding box should not be null", m.boundingBox)

        assertArrayEquals("Bounding Box minimum",
            expectedMin.toFloatArray().toTypedArray(),
            m.boundingBox!!.min.toFloatArray().toTypedArray())
        assertArrayEquals("Bounding Box maximum",
            expectedMax.toFloatArray().toTypedArray(),
            m.boundingBox!!.max.toFloatArray().toTypedArray())

        val empty = Mesh()
        empty.boundingBox = empty.generateBoundingBox()
        assertArrayEquals("Expected empty bounding box",
            empty.boundingBox!!.min.toFloatArray().toTypedArray(),
            Vector3f(0.0f, 0.0f, 0.0f).toFloatArray().toTypedArray())
        assertArrayEquals("Expected empty bounding box",
            empty.boundingBox!!.max.toFloatArray().toTypedArray(),
            Vector3f(0.0f, 0.0f, 0.0f).toFloatArray().toTypedArray())

        empty.addChild(m)
        empty.boundingBox = empty.generateBoundingBox()
        assertArrayEquals("Bounding Box minimum",
            expectedMin.toFloatArray().toTypedArray(),
            empty.boundingBox!!.min.toFloatArray().toTypedArray())
        assertArrayEquals("Bounding Box maximum",
            expectedMax.toFloatArray().toTypedArray(),
            empty.boundingBox!!.max.toFloatArray().toTypedArray())
    }

    /**
     * Tests centering for Nodes.
     */
    @Test
    fun testCentering() {
        val m = Mesh()
        m.geometry().vertices = BufferUtils.allocateFloatAndPut(
            floatArrayOf(-1.0f, -1.0f, -1.0f,
                1.0f, 1.0f, 1.0f))
        m.boundingBox = m.generateBoundingBox()

        val expectedCenter = Vector3f(1.0f, 1.0f, 1.0f)
        val center = m.spatial().centerOn(Vector3f(0.0f, 0.0f, 0.0f))
        logger.info("Centering on $center, expected=$expectedCenter")

        assertArrayEquals("Centering on $center",
            expectedCenter.toFloatArray().toTypedArray(),
            center.toFloatArray().toTypedArray())
    }

    /**
     * Tests fitting Nodes uniformly into a box of a given side length.
     */
    @Test
    fun testFitting() {
        val m = Mesh()
        m.geometry().vertices = BufferUtils.allocateFloatAndPut(
            floatArrayOf(-1.0f, -2.0f, -4.0f,
                1.0f, 2.0f, 4.0f))
        m.boundingBox = m.generateBoundingBox()
        val scaling = m.spatial().fitInto(0.5f)
        val expectedScaling = Vector3f(0.0625f, 0.0625f, 0.0625f)

        logger.info("Applied scaling: $scaling, expected=$expectedScaling")

        assertArrayEquals("Fitting into box size of side length 0.5f",
            expectedScaling.toFloatArray().toTypedArray(),
            scaling.toFloatArray().toTypedArray())
    }

    /**
     * Tests getting to the root scene object from connected and unconnected Nodes.
     */
    @Test
    fun testGetScene() {
        val scene = Scene()
        val n1 = RichNode()
        val n2 = RichNode()
        scene.addChild(n1)

        assertEquals(scene, n1.getScene(), "Expected node scene is attached to to be $scene, but is ${n1.getScene()}")
        assertNull(n2.getScene(), "Expected scene of $n2 to be null, as it is not attached to a scene." )
    }

    /**
     * Tests triggering model/world updates after setting the position of the Node.
     */
    @Test
    fun testPositionChangeTriggersUpdate() {
        val scene = Scene()
        val node = RichNode()
        scene.addChild(node)

        assertTrue(node.spatial().needsUpdate, "Expected node to need update after creation")
        assertTrue(node.spatial().needsUpdateWorld, "Expected node to need world update after creation")

        node.spatial().position = Random.random3DVectorFromRange(-100.0f, 100.0f)
        assertTrue(node.spatial().needsUpdate, "Expected node to need update after position change")
        assertTrue(node.spatial().needsUpdateWorld, "Expected node to need world update after position change")

        scene.spatial().updateWorld(true)

        assertFalse(node.spatial().needsUpdate, "Expected node to not need update after updating manually")
        assertFalse(node.spatial().needsUpdateWorld, "Expected node not to need world update after updating manually")
    }

    /**
     * Tests triggering model/world updates after setting the scale of the Node.
     */
    @Test
    fun testScaleChangeTriggersUpdate() {
        val scene = Scene()
        val node = RichNode()
        scene.addChild(node)

        assertTrue(node.spatial().needsUpdate, "Expected node to need update after creation")
        assertTrue(node.spatial().needsUpdateWorld, "Expected node to need world update after creation")

        node.spatial().scale = Random.random3DVectorFromRange(0.0f, 1.0f)
        assertTrue(node.spatial().needsUpdate, "Expected node to need update after position change")
        assertTrue(node.spatial().needsUpdateWorld, "Expected node to need world update after position change")

        scene.spatial().updateWorld(true)

        assertFalse(node.spatial().needsUpdate, "Expected node to not need update after updating manually")
        assertFalse(node.spatial().needsUpdateWorld, "Expected node not to need world update after updating manually")
    }

    /**
     * Tests triggering model/world updates after setting the rotation of the Node.
     */
    @Test
    fun testRotationChangeTriggersUpdate() {
        val scene = Scene()
        val node = RichNode()
        scene.addChild(node)

        assertTrue(node.spatial().needsUpdate, "Expected node to need update after creation")
        assertTrue(node.spatial().needsUpdateWorld, "Expected node to need world update after creation")

        node.spatial().rotation = Random.randomQuaternion()
        assertTrue(node.spatial().needsUpdate, "Expected node to need update after position change")
        assertTrue(node.spatial().needsUpdateWorld, "Expected node to need world update after position change")

        scene.spatial().updateWorld(true)

        assertFalse(node.spatial().needsUpdate, "Expected node to not need update after updating manually")
        assertFalse(node.spatial().needsUpdateWorld, "Expected node not to need world update after updating manually")
    }

    /**
     * Tests running recursive operations on Nodes
     */
    @Test
    fun testNodeRecursion() {
        val parent = RichNode()
        val child1 = RichNode()
        val child2 = RichNode()
        val grandchild = RichNode()

        val myShinyNewMaterial = DefaultMaterial()

        parent.addChild(child1)
        parent.addChild(child2)

        child1.addChild(grandchild)

        parent.runRecursive { it.addAttribute(Material::class.java, myShinyNewMaterial) }

        assertEquals("Parent of $child1 should be $parent", parent, child1.parent)
        assertEquals("Parent of $child2 should be $parent", parent, child2.parent)

        assertEquals("Material of parent should be $myShinyNewMaterial", myShinyNewMaterial, parent.material())
        assertEquals("Material of child1 should be $myShinyNewMaterial", myShinyNewMaterial, child1.material())
        assertEquals("Material of child2 should be $myShinyNewMaterial", myShinyNewMaterial, child2.material())
        assertEquals("Material of grandchild should be $myShinyNewMaterial", myShinyNewMaterial, grandchild.material())

        parent.visible = false
        parent.runRecursive { assertFalse(it.visible, "Child node should be invisible") }
    }

    /**
     * Tests running recursive operations on Nodes
     */
    @Test
    fun testNodeRecursionJavaConsumer() {
        val parent = RichNode()
        val child1 = RichNode()
        val child2 = RichNode()
        val grandchild = RichNode()

        val myShinyNewMaterial = DefaultMaterial()

        parent.addChild(child1)
        parent.addChild(child2)

        child1.addChild(grandchild)

        parent.runRecursive(Consumer { it.addAttribute(Material::class.java, myShinyNewMaterial) })

        assertEquals("Material of parent should be $myShinyNewMaterial", myShinyNewMaterial, parent.material())
        assertEquals("Material of child1 should be $myShinyNewMaterial", myShinyNewMaterial, child1.material())
        assertEquals("Material of child2 should be $myShinyNewMaterial", myShinyNewMaterial, child2.material())
        assertEquals("Material of grandchild should be $myShinyNewMaterial", myShinyNewMaterial, grandchild.material())
    }

    /**
     * Tests Node triggers
     */
    @Test
    fun testNodeTriggers() {
        val s = Scene()
        var childrenAdded = 0
        var childrenRemoved = 0
        s.onChildrenAdded.put("childAddCounter") { _, _ -> childrenAdded++ }
        s.onChildrenRemoved.put("childRemovedCounter") { _, _ -> childrenRemoved++ }

        val nodesCount = addSiblings(s, 5, 0, 5)
        Thread.sleep(200)
        assertTrue(childrenAdded in 1..nodesCount)

        s.runRecursive { p -> p.children.forEach { p.removeChild(it) } }
        Thread.sleep(200)
        assertTrue(childrenRemoved in 1..(nodesCount - 1))
    }

    /**
     * Tests shader properties
     */
    @Test
    fun testShaderProperties() {
        val randomInt = kotlin.random.Random.nextInt()
        val n = object: RichNode("MyNode") {
            @ShaderProperty val myShaderProperty = randomInt
        }

        assertEquals(randomInt, n.getShaderProperty("myShaderProperty"),
            "Expected value from shader property to be")

        val m = object: RichNode("MyOtherNode") {
            @ShaderProperty val shaderProperties = HashMap<String, Any>()
        }

        m.shaderProperties["myHashMapProperty"] = randomInt

        assertEquals(randomInt, m.getShaderProperty("myHashMapProperty"),
            "Expected value from shader property hash map to be")
    }

    /**
     * Tests node DFS search
     */
    @Test
    fun testNodeDepthSearch() {
        val s = Scene()
        val totalNodes = addSiblings(s, 10, 0, 5)

        assertEquals(totalNodes, Node.discover(s, { it.visible == true }).size,
            "Total number of nodes seen should be $totalNodes, but is ")
    }

    /**
     * Tests bounding box for nodes with children
     */
    @Test
    fun testMaximumBoundingBox() {
        val parent = Group()
        parent.spatial().position = Vector3f(100f, 100f, 100f)
        ( 0 until 2).forEach {i ->
            val sphere = Sphere(5f, 3)
            sphere.spatial().position = Vector3f(-100f * ( i % 2 ), 0f, i.toFloat())
            parent.addChild(sphere)
        }

        val boundingSphere = parent.getMaximumBoundingBox().getBoundingSphere()

        assertEquals("Bounding sphere radius should be " + boundingSphere.radius + ", but is ", boundingSphere.radius, 53.662083f, 0.001f)
    }

    /**
     * Tests imglib2 method implementations
     */
    @Test
    fun testImglib2Implementations() {
        val n = RichNode("testnode")

        assertEquals( 3, n.spatial().numDimensions())

        n.spatial().position = Vector3f(0f, 17f, 0f)
        assertEquals( 17f, n.spatial().getFloatPosition(1), 0.01f)

        n.spatial().position = Vector3f(0f, 17f, 0f)
        n.spatial().move( -1, 1 )
        assertEquals( 16f, n.spatial().position.y(), 0.01f)

        n.spatial().position = Vector3f(0f, 17f, 0f)
        n.spatial().move(RealPoint(0.0, -1.0, 0.0))
        assertEquals( 16f, n.spatial().position.y(), 0.01f)

        n.spatial().position = Vector3f(0f, 17f, 0f)
        n.spatial().fwd(0)
        assertEquals( 1f, n.spatial().position.x(), 0.01f)

        n.spatial().position = Vector3f(0f, 17f, 0f)
        n.spatial().setPosition(doubleArrayOf(17.0, 23.0, -19.0))
        assertEquals( 17.0f, n.spatial().position.x(), 0.01f)
        assertEquals( 23.0f, n.spatial().position.y(), 0.01f)
        assertEquals( -19.0f, n.spatial().position.z(), 0.01f)

        val pos = FloatArray(3)
        n.spatial().position = Vector3f(0f, 17f, 0f)
        n.spatial().localize(pos)
        assertEquals( 17f, pos[1], 0.01f)
    }
}
