package graphics.scenery.tests.unit.controls

import graphics.scenery.mesh.Box
import graphics.scenery.Hub
import graphics.scenery.Scene
import graphics.scenery.Settings
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.SwingMouseAndKeyHandler
import graphics.scenery.tests.unit.backends.FauxRenderer
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.ui.behaviour.DragBehaviour
import org.scijava.ui.behaviour.GlobalKeyEventDispatcher
import org.scijava.ui.behaviour.ScrollBehaviour
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [SwingMouseAndKeyHandler].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class SwingMouseAndKeyHandlerTests {
    private val logger by LazyLogger()

    private fun prepareInputHandler(preparedWindow: SceneryWindow? = null): Triple<InputHandler, Scene, SwingMouseAndKeyHandler> {
        val h = Hub()
        val settings = Settings(h)
        h.add(settings)
        val scene = Scene()
        val box = Box()
        scene.addChild(box)

        val renderer = FauxRenderer(h, scene, preparedWindow)
        h.add(renderer)

        val inputHandler = InputHandler(scene, renderer, h, forceHandler = SwingMouseAndKeyHandler::class.java)

        return Triple(inputHandler, scene, inputHandler.handler as SwingMouseAndKeyHandler)
    }

    /**
     * Tests input handler initialisation.
     */
    @Test
    fun testInitialisation() {
        logger.info("Testing input handler initialisation ...")
        val (inputHandler, scene, swingHandler) = prepareInputHandler()

        assertNotNull(inputHandler)
        assertNotNull(scene)
        assertNotNull(swingHandler)
    }

    /**
     * Tests the key press handlers with a series of key events.
     */
    @Test
    fun testKeyPressedHandler() {
        logger.info("Testing input handler key press handlers ...")
        val (inputHandler, _, swingHandler) = prepareInputHandler()

        repeat(100) {
            val key = Random.nextInt(0x41, 0x5A)
            var keyPressed = false

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val pressEvent = KeyEvent(
                object: Component() {},
                KeyEvent.KEY_PRESSED,
                System.nanoTime(),
                0,
                key,
                KeyEvent.CHAR_UNDEFINED
            )

            val releaseEvent = KeyEvent(
                object: Component() {},
                KeyEvent.KEY_RELEASED,
                System.nanoTime(),
                0,
                key,
                KeyEvent.CHAR_UNDEFINED
            )

            val char = KeyEvent.getKeyText(key)

            inputHandler.addBehaviour("keyPressed_$char", ClickBehaviour { _, _ ->
                keyPressed = true
            })
            inputHandler.addKeyBinding("keyPressed_$char", char)

            swingHandler.keyPressed(pressEvent)
            swingHandler.keyReleased(releaseEvent)

            assertTrue(keyPressed, "Expected key to be pressed for $key/$key/$char")
        }
    }

    private fun String.toMask(): Int {
        var mask = 0
        if(this.contains("shift")) {
            mask = mask or InputEvent.SHIFT_DOWN_MASK
        }

        if(this.contains("alt")) {
            mask = mask or InputEvent.ALT_DOWN_MASK
        }

        if(this.contains("ctrl")) {
            mask = mask or InputEvent.CTRL_DOWN_MASK
        }

        if(this.contains("meta")) {
            mask = mask or InputEvent.META_DOWN_MASK
        }

        return mask
    }

    private fun String.toEvents(): List<Pair<KeyEvent, KeyEvent>> {
        val events = mutableListOf<Pair<KeyEvent, KeyEvent>>()
        if(this.contains("shift")) {
            events.add(KeyEvent(
                object: Component() {},
                KeyEvent.KEY_PRESSED,
                System.nanoTime(),
                0,
                KeyEvent.VK_SHIFT,
                KeyEvent.CHAR_UNDEFINED
            ) to KeyEvent(
                object: Component() {},
                KeyEvent.KEY_RELEASED,
                System.nanoTime(),
                0,
                KeyEvent.VK_SHIFT,
                KeyEvent.CHAR_UNDEFINED
            ))
        }

        if(this.contains("alt")) {
            events.add(KeyEvent(
                object: Component() {},
                KeyEvent.KEY_PRESSED,
                System.nanoTime(),
                0,
                KeyEvent.VK_ALT,
                KeyEvent.CHAR_UNDEFINED
            ) to KeyEvent(
                object: Component() {},
                KeyEvent.KEY_RELEASED,
                System.nanoTime(),
                0,
                KeyEvent.VK_ALT,
                KeyEvent.CHAR_UNDEFINED
            ))
        }

        if(this.contains("ctrl")) {
            events.add(KeyEvent(
                object: Component() {},
                KeyEvent.KEY_PRESSED,
                System.nanoTime(),
                0,
                KeyEvent.VK_CONTROL,
                KeyEvent.CHAR_UNDEFINED
            ) to KeyEvent(
                object: Component() {},
                KeyEvent.KEY_RELEASED,
                System.nanoTime(),
                0,
                KeyEvent.VK_CONTROL,
                KeyEvent.CHAR_UNDEFINED
            ))
        }

        if(this.contains("meta")) {
            events.add(KeyEvent(
                object: Component() {},
                KeyEvent.KEY_PRESSED,
                System.nanoTime(),
                0,
                KeyEvent.VK_META,
                KeyEvent.CHAR_UNDEFINED
            ) to KeyEvent(
                object: Component() {},
                KeyEvent.KEY_RELEASED,
                System.nanoTime(),
                0,
                KeyEvent.VK_META,
                KeyEvent.CHAR_UNDEFINED
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
        val (inputHandler, _, swingHandler) = prepareInputHandler()
        val dispatcher = GlobalKeyEventDispatcher.getInstance()

        repeat(100) { i ->
            val button = Random.nextInt(1, 4)
            var keyPressed = false

            val modifiers = listOf(
                "",
//                "ctrl",
//                "ctrl alt",
//                "ctrl shift",
//                "ctrl alt shift",
//                "alt shift",
//                "alt",
// TODO: Meta handling seems broken on OSX, investigate!
//                "meta",
                "shift").random()

            val modifiersFull = when(button) {
                1 -> MouseEvent.BUTTON1_DOWN_MASK
                2 -> MouseEvent.BUTTON2_DOWN_MASK
                else -> MouseEvent.BUTTON3_DOWN_MASK
            } or modifiers.toMask()
            val modifierEvents = modifiers.toEvents()

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val clickCount = Random.nextInt(1, 3)
            val point = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))

            val clickEvent = MouseEvent(
                object: Component() {},
                MouseEvent.MOUSE_CLICKED,
                System.nanoTime(),
                modifiersFull,
                point.first,
                point.second,
                point.first,
                point.second,
                clickCount,
                false,
                button)

            val clicks = when(clickCount) {
                1 -> ""
                else -> "double-click "
            }

            val buttonString = when(button) {
                1 -> "button1"
                2 -> "button2"
                else -> "button3"
            }

            logger.debug("Mask is $modifiersFull, modifiers=$modifiers, button=$buttonString")

            modifierEvents.forEach {
                swingHandler.keyPressed(it.first)
                dispatcher.dispatchKeyEvent(it.first)
            }

            var actualPoint = Pair(0, 0)
            inputHandler.addBehaviour("mousePressed_$i", ClickBehaviour { x, y ->
                actualPoint = Pair(x, y)
                keyPressed = true
            })
            inputHandler.addKeyBinding("mousePressed_$i", "$modifiers $clicks$buttonString")

            swingHandler.mouseClicked(clickEvent)

            modifierEvents.forEach {
                swingHandler.keyReleased(it.second)
                dispatcher.dispatchKeyEvent(it.second)
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
        val (inputHandler, _, swingHandler) = prepareInputHandler()
        val dispatcher = GlobalKeyEventDispatcher.getInstance()

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
// TODO: Meta handling seems broken on OSX, investigate!
//              "meta"
                "shift").random()

            val modifierEvents = modifiers.toEvents()

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val point = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))
            val amount = Random.nextInt(-1000, 1000)

            val scrollEvent = MouseWheelEvent(
                object: Component() {},
                MouseEvent.MOUSE_WHEEL,
                System.nanoTime(),
                modifiers.toMask(),
                point.first,
                point.second,
                point.first,
                point.second,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                amount,
                amount,
                amount.toDouble())

            logger.debug("Mask is ${modifiers.toMask()}, modifiers=$modifiers")

            modifierEvents.forEach {
                dispatcher.dispatchKeyEvent(it.first)
                swingHandler.keyPressed(it.first)
            }

            inputHandler.addBehaviour("mouseScrolled_$i", object: ScrollBehaviour {
                override fun scroll(p0: Double, p1: Boolean, p2: Int, p3: Int) {
                    scrolled = true
                }
            })
            inputHandler.addKeyBinding("mouseScrolled_$i", "$modifiers scroll")

            swingHandler.mouseWheelMoved(scrollEvent)

            modifierEvents.forEach {
                dispatcher.dispatchKeyEvent(it.second)
                swingHandler.keyReleased(it.second)
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
        val (inputHandler, _, swingHandler) = prepareInputHandler()
        val dispatcher = GlobalKeyEventDispatcher.getInstance()

        repeat(100) { i ->
            val button = Random.nextInt(1, 3)
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
// TODO: Meta handling seems broken on OSX, investigate!
//                "meta",
                "shift").random()

            val modifiersFull = when(button) {
                1 -> MouseEvent.BUTTON1_DOWN_MASK
                2 -> MouseEvent.BUTTON2_DOWN_MASK
                else -> MouseEvent.BUTTON3_DOWN_MASK
            } or modifiers.toMask()
            val modifiersRelease = modifiers.toMask()
            val modifierEvents = modifiers.toEvents()

            // we create a press and release event here, otherwise
            // the keys are assumed to be down simultaneously
            val start = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))
            val end = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))

            val clickEvent = MouseEvent(
                object: Component() {},
                MouseEvent.MOUSE_PRESSED,
                System.nanoTime(),
                modifiersFull,
                start.first,
                start.second,
                start.first,
                start.second,
                1,
                false,
                button)

            val moveEvents = (0.. Random.nextInt(5, 10)).map {
                val position = Pair(Random.nextInt(0, 8192), Random.nextInt(0, 8192))
                MouseEvent(
                    object: Component() {},
                    MouseEvent.MOUSE_CLICKED,
                    System.nanoTime(),
                    modifiersFull,
                    position.first,
                    position.second,
                    position.first,
                    position.second,
                    0,
                    false,
                    button)
            }

            val dragCount = moveEvents.size
            var actualDrags = 0

            val releaseEvent = MouseEvent(
                object: Component() {},
                MouseEvent.MOUSE_RELEASED,
                System.nanoTime(),
                modifiersRelease,
                end.first,
                end.second,
                end.first,
                end.second,
                0,
                false,
                button)

            val buttonString = when(button) {
                1 -> "button1"
                2 -> "button2"
                else -> "button3"
            }

            logger.debug("Mask is $modifiersFull, modifiers=$modifiers")

            modifierEvents.forEach {
                dispatcher.dispatchKeyEvent(it.first)
                swingHandler.keyPressed(it.first)
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

            swingHandler.mousePressed(clickEvent)
            moveEvents.forEach { move ->
                swingHandler.mouseMoved(move)
                swingHandler.mouseDragged(move)
            }
            swingHandler.mouseReleased(releaseEvent)

            modifierEvents.forEach {
                dispatcher.dispatchKeyEvent(it.second)
                swingHandler.keyReleased(it.second)
            }

            assertEquals(start, dragStart, "Expected dragging start to be")
            assertEquals(end, dragEnd, "Expected dragging end to be")
            assertEquals(dragCount, actualDrags, "Expected $dragCount events to have happened")
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

        assertFailsWith(UnsupportedOperationException::class, "Expected SwingMouseAndKeyHandler not to support BrokenWindow") {
            prepareInputHandler(preparedWindow = broken)
        }
    }
}
