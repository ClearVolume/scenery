package graphics.scenery.tests.unit.controls

import com.jogamp.newt.event.KeyEvent
import com.jogamp.newt.event.MouseEvent
import graphics.scenery.mesh.Box
import graphics.scenery.Hub
import graphics.scenery.Scene
import graphics.scenery.Settings
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.controls.GLFWMouseAndKeyHandler
import graphics.scenery.controls.InputHandler
import graphics.scenery.tests.unit.backends.FauxRenderer
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import org.lwjgl.glfw.GLFW.*
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.ui.behaviour.DragBehaviour
import org.scijava.ui.behaviour.ScrollBehaviour
import java.lang.Math.pow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [GLFWMouseAndKeyHandler].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class GLFWMouseAndKeyHandlerTests {
    private val logger by LazyLogger()

    private fun prepareInputHandler(preparedWindow: SceneryWindow? = null): Triple<InputHandler, Scene, GLFWMouseAndKeyHandler> {
        val h = Hub()
        val settings = Settings(h)
        h.add(settings)
        val scene = Scene()
        val box = Box()
        scene.addChild(box)

        val renderer = FauxRenderer(h, scene, preparedWindow)
        h.add(renderer)

        val inputHandler = InputHandler(scene, renderer, h, forceHandler = GLFWMouseAndKeyHandler::class.java)

        return Triple(inputHandler, scene, inputHandler.handler as GLFWMouseAndKeyHandler)
    }

    /**
     * Tests input handler initialisation.
     */
    @Test
    fun testInitialisation() {
        logger.info("Testing input handler initialisation ...")
        val (inputHandler, scene, glfwHandler) = prepareInputHandler()

        assertNotNull(inputHandler)
        assertNotNull(scene)
        assertNotNull(glfwHandler)
    }

    /**
     * Tests the key press handlers with a series of key events.
     */
    @Test
    fun testKeyPressedHandler() {
        logger.info("Testing input handler key press handlers ...")
        val (inputHandler, _, glfwHandler) = prepareInputHandler()

        repeat(100) {
            val key = Random.nextInt(0x41, 0x5A).toShort()
            var keyPressed = false

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val pressEvent = KeyEvent.create(
                KeyEvent.EVENT_KEY_PRESSED,
                {},
                System.nanoTime(),
                0,
                key,
                key,
                KeyEvent.NULL_CHAR
            )

            val releaseEvent = KeyEvent.create(
                KeyEvent.EVENT_KEY_RELEASED,
                {},
                System.nanoTime(),
                0,
                key,
                key,
                KeyEvent.NULL_CHAR
            )

            val char = java.awt.event.KeyEvent.getKeyText(key.toInt())

            inputHandler.addBehaviour("keyPressed_$char", ClickBehaviour { _, _ ->
                keyPressed = true
            })
            inputHandler.addKeyBinding("keyPressed_$char", char)

            glfwHandler.keyPressed(pressEvent)
            glfwHandler.keyReleased(releaseEvent)

            assertTrue(keyPressed, "Expected key to be pressed for $key/$key/$char")
        }
    }

    private fun String.toMask(): Int {
        var mask = 0
        if(this.contains("shift")) {
            mask = mask or GLFW_MOD_SHIFT
        }

        if(this.contains("alt")) {
            mask = mask or GLFW_MOD_ALT
        }

        if(this.contains("ctrl")) {
            mask = mask or GLFW_MOD_CONTROL
        }

        return mask
    }

    private fun String.toEvents(): List<Pair<KeyEvent, KeyEvent>> {
        val events = mutableListOf<Pair<KeyEvent, KeyEvent>>()
        if(this.contains("shift")) {
            events.add(KeyEvent.create(
                KeyEvent.EVENT_KEY_PRESSED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_SHIFT.toShort(),
                GLFW_KEY_LEFT_SHIFT.toShort(),
                KeyEvent.NULL_CHAR
            ) to KeyEvent.create(KeyEvent.EVENT_KEY_RELEASED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_SHIFT.toShort(),
                GLFW_KEY_LEFT_SHIFT.toShort(),
                KeyEvent.NULL_CHAR
            ))
        }

        if(this.contains("alt")) {
            events.add(KeyEvent.create(
                KeyEvent.EVENT_KEY_PRESSED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_ALT.toShort(),
                GLFW_KEY_LEFT_ALT.toShort(),
                KeyEvent.NULL_CHAR
            ) to KeyEvent.create(
                KeyEvent.EVENT_KEY_RELEASED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_ALT.toShort(),
                GLFW_KEY_LEFT_ALT.toShort(),
                KeyEvent.NULL_CHAR
            ))
        }

        if(this.contains("ctrl")) {
            events.add(KeyEvent.create(
                KeyEvent.EVENT_KEY_PRESSED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_CONTROL.toShort(),
                GLFW_KEY_LEFT_CONTROL.toShort(),
                KeyEvent.NULL_CHAR
            ) to KeyEvent.create(
                KeyEvent.EVENT_KEY_RELEASED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_CONTROL.toShort(),
                GLFW_KEY_LEFT_CONTROL.toShort(),
                KeyEvent.NULL_CHAR
            ))
        }

        if(this.contains("meta")) {
            events.add(KeyEvent.create(
                KeyEvent.EVENT_KEY_PRESSED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_SUPER.toShort(),
                GLFW_KEY_LEFT_SUPER.toShort(),
                KeyEvent.NULL_CHAR
            ) to KeyEvent.create(
                KeyEvent.EVENT_KEY_RELEASED,
                {},
                System.nanoTime(),
                0,
                GLFW_KEY_LEFT_SUPER.toShort(),
                GLFW_KEY_LEFT_SUPER.toShort(),
                KeyEvent.NULL_CHAR
            ))
        }

        return events
    }

    /**
     * Tests the click handlers with a series of events and modifier keys.
     */
    @Test
    fun testMouseClickedHandlers() {
        logger.info("Testing input handler mouse press handlers with modifiers ...")
        val (inputHandler, _, glfwHandler) = prepareInputHandler()

        repeat(100) { i ->
            val button = Random.nextInt(1, 4).toShort()
            var keyPressed = false

            val modifiers = listOf(
                "",
//                "ctrl",
//                "ctrl alt",
//                "ctrl shift",
//                "ctrl alt shift",
//                "alt shift",
//                "alt",
                "shift").random()

            val modifiersFull = pow(2.0, 4.0+button).toInt() or modifiers.toMask()
            val modifierEvents = modifiers.toEvents()

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val clickCount = Random.nextInt(1, 3).toShort()
            val point = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))
            val clickEvent = MouseEvent(MouseEvent.EVENT_MOUSE_CLICKED,
                {},
                System.nanoTime(),
                modifiersFull,
                point.first,
                point.second,
                clickCount,
                button,
                floatArrayOf(0.0f, 0.0f, 0.0f),
                1.0f)

            val clicks = when(clickCount) {
                1.toShort() -> ""
                else -> "double-click "
            }

            val buttonString = when(button) {
                1.toShort() -> "button1"
                2.toShort() -> "button2"
                else -> "button3"
            }

            logger.debug("Mask is $modifiersFull, modifiers=$modifiers")

            modifierEvents.forEach {
                glfwHandler.keyPressed(it.first)
            }

            var actualPoint = Pair(0, 0)
            inputHandler.addBehaviour("mousePressed_$i", ClickBehaviour { x, y ->
                actualPoint = Pair(x, y)
                keyPressed = true
            })
            inputHandler.addKeyBinding("mousePressed_$i", "$modifiers $clicks$buttonString")

            glfwHandler.mouseClicked(clickEvent)

            modifierEvents.forEach {
                glfwHandler.keyReleased(it.second)
            }

            assertEquals(point, actualPoint, "Expected click points to be equal")
            assertTrue(keyPressed, "Expected mouse to be clicked for $button/count=$clickCount, '$modifiers $clicks$buttonString'")
            logger.debug("Mouse clicked for $modifiers $clicks $buttonString")
        }
    }

    /**
     * Tests the mouse wheel handlers with a series of events and modifier keys.
     */
    @Test
    fun testMouseWheelMovedHandlers() {
        logger.info("Testing input handler mouse scroll handlers with modifiers ...")
        val (inputHandler, _, glfwHandler) = prepareInputHandler()

        repeat(100) { i ->
            var scrolled = false

            val modifiers = listOf(
                "",
//                "ctrl",
//                "ctrl alt",
//                "ctrl shift",
//                "ctrl alt shift",
//                "alt shift",
//                "alt",
                "shift").random()

            val modifierEvents = modifiers.toEvents()

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val point = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))
            val scroll = when(Random.nextInt(0, 3)) {
                0 -> Pair(Random.nextFloat(), 0.0f)
                1 -> Pair(0.0f, Random.nextFloat())
                else -> Pair(Random.nextFloat(), Random.nextFloat())
            }

            val scrollEvent = MouseEvent(MouseEvent.EVENT_MOUSE_WHEEL_MOVED,
                {},
                System.nanoTime(),
                modifiers.toMask(),
                point.first,
                point.second,
                0,
                0,
                floatArrayOf(scroll.first, scroll.second, 0.0f),
                1.0f)

            logger.debug("Mask is ${modifiers.toMask()}, modifiers=$modifiers")

            modifierEvents.forEach {
                glfwHandler.keyPressed(it.first)
            }

            inputHandler.addBehaviour("mouseScrolled_$i", object: ScrollBehaviour {
                override fun scroll(p0: Double, p1: Boolean, p2: Int, p3: Int) {
                    scrolled = true
                }
            })
            inputHandler.addKeyBinding("mouseScrolled_$i", "$modifiers scroll")

            glfwHandler.mouseWheelMoved(scrollEvent)

            modifierEvents.forEach {
                glfwHandler.keyReleased(it.second)
            }

            assertTrue(scrolled, "Expected scroll event to have happened")
        }
    }

    /**
     * Tests the dragging handlers by creating a series of drag events
     * with different mouse buttons and modifiers.
     */
    @Test
    fun testMouseDraggedHandlers() {
        logger.info("Testing input handler dragging handlers ...")
        val (inputHandler, _, glfwHandler) = prepareInputHandler()

        repeat(100) { i ->
            // FIXME: Why does button 2 not work?
            val button = Random.nextInt(1, 2).toShort()
            var dragStart = Pair(0, 0)
            var dragEnd = Pair(0, 0)

            val modifiers = listOf(
                "",
//                "ctrl",
//                "ctrl alt",
//                "ctrl shift",
//                "ctrl alt shift",
//                "alt shift",
//                "alt",
                "shift").random()

            val modifiersFull = pow(2.0, 4.0+button).toInt() or modifiers.toMask()
            val modifiersRelease = modifiers.toMask()
            val modifierEvents = modifiers.toEvents()

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val start = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))
            val end = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))

            val clickEvent = MouseEvent(MouseEvent.EVENT_MOUSE_PRESSED,
                {},
                System.nanoTime(),
                modifiersFull,
                start.first,
                start.second,
                0,
                button,
                floatArrayOf(0.0f, 0.0f, 0.0f),
                1.0f)

            val moveEvents = (0.. Random.nextInt(5, 10)).map {
                MouseEvent(MouseEvent.EVENT_MOUSE_MOVED,
                    {},
                    System.nanoTime(),
                    modifiersFull,
                    Random.nextInt(0, 8192),
                    Random.nextInt(0, 8192),
                    0,
                    button,
                    floatArrayOf(0.0f, 0.0f, 0.0f),
                    1.0f)
            }

            val dragCount = moveEvents.size
            var actualDrags = 0

            val releaseEvent = MouseEvent(MouseEvent.EVENT_MOUSE_RELEASED,
                {},
                System.nanoTime(),
                modifiersRelease,
                end.first,
                end.second,
                0,
                button,
                floatArrayOf(0.0f, 0.0f, 0.0f),
                1.0f)

            val buttonString = when(button) {
                1.toShort() -> "button1"
                2.toShort() -> "button2"
                else -> "button3"
            }

            logger.debug("Mask is $modifiersFull, modifiers=$modifiers")

            modifierEvents.forEach {
                glfwHandler.keyPressed(it.first)
            }

            inputHandler.addBehaviour("dragged_$i", object: DragBehaviour {
                override fun drag(p0: Int, p1: Int) {
                    actualDrags++
                }

                override fun end(p0: Int, p1: Int) {
                    dragEnd = Pair(p0, p1)
                }

                override fun init(p0: Int, p1: Int) {
                    dragStart = Pair(p0, p1)
                }
            })
            inputHandler.addKeyBinding("dragged_$i", "$modifiers $buttonString")

            glfwHandler.mousePressed(clickEvent)
            moveEvents.forEach { move ->
                glfwHandler.mouseMoved(move)
                glfwHandler.mouseDragged(move)
            }
            glfwHandler.mouseReleased(releaseEvent)

            modifierEvents.forEach {
                glfwHandler.keyReleased(it.second)
            }

            assertEquals(start, dragStart, "Expected dragging start to be")
            assertEquals(end, dragEnd, "Expected dragging end to be")
            assertEquals(dragCount * 2, actualDrags, "Expected $dragCount events to have happened")
        }
    }

    /**
     * Tests that the input handler fails predictably with an unknown window type.
     */
    @Test
    fun testUnsupportedWindowType() {
        logger.info("Testing unsupported window type ...")

        /**
         * Window class scenery has no idea about.
         */
        class BrokenWindow: SceneryWindow()
        val broken = BrokenWindow()

        assertFailsWith(UnsupportedOperationException::class, "Expeceted glfwMouseAndKeyHandler not to support BrokenWindow") {
            prepareInputHandler(preparedWindow = broken)
        }
    }
}
