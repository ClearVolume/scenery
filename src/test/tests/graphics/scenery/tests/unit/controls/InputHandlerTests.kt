package graphics.scenery.tests.unit.controls

import graphics.scenery.mesh.Box
import graphics.scenery.Hub
import graphics.scenery.Scene
import graphics.scenery.Settings
import graphics.scenery.controls.InputHandler
import graphics.scenery.tests.unit.backends.FauxRenderer
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.ui.behaviour.InputTrigger
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the input handler
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class InputHandlerTests {
    private val logger by LazyLogger()

    private fun prepareInputHandler(): InputHandler {
        val hub = Hub()
        val settings = Settings(hub)
        hub.add(settings)
        val scene = Scene()
        val box = Box()
        scene.addChild(box)

        val renderer = FauxRenderer(hub, scene)
        hub.add(renderer)

        val inputHandler = InputHandler(scene, renderer, hub)
        inputHandler.useDefaultBindings("doesnotexist.config")

        return inputHandler
    }

    @Test
    fun testInitialisation() {
        logger.info("Testing InputHandler initialisation...")
        val inputHandler = prepareInputHandler()
        assertTrue(inputHandler.behaviourMap.allBindings.isNotEmpty(), "InputHandler has more than zero bindings after default initialisation")
    }

    @Test
    fun testAddBehaviour() {
        logger.info("Testing adding a behaviour...")
        val inputHandler = prepareInputHandler()
        inputHandler.addBehaviour("testbehaviour", ClickBehaviour { _, _ ->
            logger.info("Doing nothing")
        })

        assertNotNull(inputHandler.getBehaviour("testbehaviour"), "Querying behaviour from InputHandler results in non-null return after adding it")
    }

    @Test
    fun testTriggerBehaviour() {
        logger.info("Testing triggering a behaviour...")
        var triggered = false
        val inputHandler = prepareInputHandler()
        inputHandler.addBehaviour("testbehaviour", ClickBehaviour { _, _ ->
            triggered = true
        })

        (inputHandler.getBehaviour("testbehaviour") as ClickBehaviour).click(0, 0)
        assertTrue(triggered, "Behaviour is triggered")
    }

    @Test
    fun testRemoveBehaviour() {
        logger.info("Testing removing a behaviour...")
        val inputHandler = prepareInputHandler()
        inputHandler.addBehaviour("testbehaviour", ClickBehaviour { _, _ ->
            logger.info("Doing nothing")
        })

        inputHandler.removeBehaviour("testbehaviour")
        assertNull(inputHandler.getBehaviour("testbehaviour"), "InputHandler has behaviour removed")
    }

    @Test
    fun testAddKeybinding() {
        logger.info("Testing adding a key binding...")
        val inputHandler = prepareInputHandler()
        inputHandler.addBehaviour("testbehaviour", ClickBehaviour { _, _ ->
            logger.info("Doing nothing")
        })

        inputHandler.addKeyBinding("testbehaviour", "A")
        assertTrue(inputHandler.getAllBindings().containsKey(InputTrigger.getFromString("A")), "InputHandler contains trigger for behaviour")
        assertTrue(inputHandler.getAllBindings().entries.any { it.value.contains("testbehaviour") }, "InputHandler contains behaviour")
    }
}
