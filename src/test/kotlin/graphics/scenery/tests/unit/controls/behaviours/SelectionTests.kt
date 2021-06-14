package graphics.scenery.tests.unit.controls.behaviours

import graphics.scenery.Box
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.Hub
import graphics.scenery.InstancedNode
import graphics.scenery.Scene
import graphics.scenery.SceneryBase
import graphics.scenery.SceneryElement
import graphics.scenery.Settings
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.tests.unit.backends.FauxRenderer
import graphics.scenery.utils.LazyLogger
import org.joml.Vector3f
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SelectionTests {
    private val logger by LazyLogger()

    /**
     * Tests initialisation of [SelectCommand].
     */
    @Test
    fun testInitialisation() {
        logger.info("Testing SelectCommand initialisation...")
        val scene = Scene()
        val hub = Hub()
        hub.add(SceneryElement.Settings, Settings())
        val renderer = FauxRenderer(hub, scene)
        hub.add(renderer)
        hub.addApplication(SceneryBase("bla", 512, 512))
        val selection = SelectCommand(
            "TestController", renderer, scene,
            { scene.findObserver() }, false, emptyList()
        )
        assertNotNull(selection)
    }

    /**
     * Tests selection.
     */
    @Test
    fun testSelection() {
        logger.info("Testing SelectCommand...")
        val scene = Scene()
        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.spatial {
            position = Vector3f(-1.0f, 0.0f, 0.0f)
            updateWorld(false)
        }
        scene.addChild(box)
        val camera = DetachedHeadCamera()
        camera.spatial {
            position = Vector3f(0.0f, 0.0f, 5.0f)
        }
        camera.perspectiveCamera(52.0f, 512, 512)
        scene.addChild(camera)
        val hub = Hub()
        hub.add(SceneryElement.Settings, Settings())
        val renderer = FauxRenderer(hub, scene)
        hub.add(renderer)
        val app = SceneryBase("Test", 512, 512)
        hub.addApplication(app)
        val matches = ArrayList<Scene.RaycastMatch>()
        val selection = SelectCommand("TestController", renderer, scene,
            { camera }, false, emptyList(), { raycastResult: Scene.RaycastResult, _: Int, _: Int ->
                matches.clear()
                matches.addAll(raycastResult.matches);
            })
        selection.click(130, 250)
        assertSame(1, matches.size)
        assertEquals(box, matches.get(0).node)
    }

    @Test
    fun testSelectionInstance() {
        logger.info("Testing SelectCommand on an instance...")
        val scene = Scene()
        val template = Box()
        template.spatial {
            updateWorld(false)
        }
        val boxInstanced = InstancedNode(template)

        val instanceNode = boxInstanced.addInstance()
        instanceNode.name = "agent"
        instanceNode.spatial {
            position = Vector3f(-1.0f, 0.0f, 0.0f)
            updateWorld(false)
        }
        scene.addChild(boxInstanced)
        val camera = DetachedHeadCamera()
        camera.spatial {
            position = Vector3f(0.0f, 0.0f, 5.0f)
        }
        camera.perspectiveCamera(52.0f, 512, 512)
        scene.addChild(camera)
        val hub = Hub()
        hub.add(SceneryElement.Settings, Settings())
        val renderer = FauxRenderer(hub, scene)
        hub.add(renderer)
        val app = SceneryBase("Test", 512, 512)
        hub.addApplication(app)
        val matches = ArrayList<Scene.RaycastMatch>()
        val selection = SelectCommand("TestController", renderer, scene,
            { camera }, false, emptyList(), { raycastResult: Scene.RaycastResult, _: Int, _: Int ->
                matches.clear()
                matches.addAll(raycastResult.matches);
            })
        // this should select the instance
        selection.click(120, 250)
        assertSame(1, matches.size)
        assertEquals(instanceNode, matches.get(0).node)
        // this should not select the base node which is at that position but not displayed
        selection.click(256, 256)
        assertSame(0, matches.size)
    }

}
