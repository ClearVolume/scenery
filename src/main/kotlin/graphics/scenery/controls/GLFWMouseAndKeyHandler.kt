package graphics.scenery.controls

import com.jogamp.newt.event.InputEvent
import com.jogamp.newt.event.KeyEvent
import com.jogamp.newt.event.MouseEvent
import com.jogamp.newt.event.WindowEvent
import graphics.scenery.Hub
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.utils.ExtractsNatives
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWCursorPosCallback
import org.lwjgl.glfw.GLFWKeyCallback
import org.lwjgl.glfw.GLFWMouseButtonCallback
import org.lwjgl.glfw.GLFWScrollCallback
import org.scijava.ui.behaviour.BehaviourMap
import org.scijava.ui.behaviour.InputTrigger
import org.scijava.ui.behaviour.InputTriggerMap

/**
 * Input handling class for GLFW-based windows.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
@CanHandleInputFor([SceneryWindow.GLFWWindow::class])
open class GLFWMouseAndKeyHandler(var hub: Hub?) : MouseAndKeyHandlerBase(), AutoCloseable, ExtractsNatives {
    /** store os name */
    private var os = ""

    /** scroll speed multiplier to combat OS idiosyncrasies */
    private var scrollSpeedMultiplier = 1.0f

    val window by lazy { hub!!.get<Renderer>()!!.window as SceneryWindow.GLFWWindow }

    var cursorCallback = object : GLFWCursorPosCallback() {
        override fun invoke(window: Long, xpos: Double, ypos: Double) {
            mouseMoved(MouseEvent(MouseEvent.EVENT_MOUSE_MOVED,
                                  this,
                                  System.nanoTime(),
                                  0,
                                  xpos.toInt(),
                                  ypos.toInt(),
                                  0, 0,
                                  floatArrayOf(0.0f, 0.0f, 0.0f), 1.0f))
        }
    }

    var keyCallback = object : GLFWKeyCallback() {
        override fun invoke(window: Long, key: Int, scancode: Int, action: Int, mods: Int) {
            val type = when (action) {
                GLFW_PRESS -> KeyEvent.EVENT_KEY_PRESSED
                GLFW_RELEASE -> KeyEvent.EVENT_KEY_RELEASED
                GLFW_REPEAT -> KeyEvent.EVENT_KEY_PRESSED
                else -> KeyEvent.EVENT_KEY_PRESSED
            }

            val event = KeyEvent.create(
                type,
                this,
                System.nanoTime(),
                mods,
                key.toShort(),
                scancode.toShort(),
                ' '
                                       )

            when (action) {
                GLFW_PRESS -> keyPressed(event)
                GLFW_REPEAT -> keyPressed(event)
                GLFW_RELEASE -> keyReleased(event)
            }
        }

    }

    private var clickBefore = System.nanoTime()
    var mouseCallback = object : GLFWMouseButtonCallback() {
        override fun invoke(window: Long, key: Int, action: Int, mods: Int) {
            val type = when (action) {
                GLFW_PRESS -> MouseEvent.EVENT_MOUSE_PRESSED
                GLFW_RELEASE -> MouseEvent.EVENT_MOUSE_RELEASED
                else -> MouseEvent.EVENT_MOUSE_CLICKED
            }

            var clickCount = 1

            if (action == GLFW_PRESS) {
                val now = System.nanoTime()
                val diff = (now - clickBefore) / 10e5

                if (diff > 10 && diff < getDoubleClickInterval()) {
                    clickCount = 2
                }

                clickBefore = now
            }

            val event = MouseEvent(type,
                                   this,
                                   System.nanoTime(),
                                   0,
                                   mouseX,
                                   mouseY,
                                   clickCount.toShort(), 0,
                                   floatArrayOf(0.0f, 0.0f, 0.0f), 1.0f)


            when (action) {
                GLFW_PRESS -> {
                    mousePressed(event); }
                GLFW_RELEASE -> {
                    mouseReleased(event); }
            }
        }
    }

    var scrollCallback = object : GLFWScrollCallback() {
        override fun invoke(window: Long, xoffset: Double, yoffset: Double) {
            mouseWheelMoved(MouseEvent(MouseEvent.EVENT_MOUSE_WHEEL_MOVED,
                                       this,
                                       System.nanoTime(),
                                       0,
                                       0,
                                       0,
                                       0, 0,
                                       floatArrayOf(xoffset.toFloat() * scrollSpeedMultiplier, yoffset.toFloat() * scrollSpeedMultiplier, 0.0f), 1.0f))
        }

    }

    init {
        os = if (System.getProperty("os.name").lowercase().indexOf("windows") != -1) {
            "windows"
        } else if (System.getProperty("os.name").lowercase().indexOf("mac") != -1) {
            "mac"
        } else if (System.getProperty("os.name").lowercase().indexOf("linux") != -1) {
            "linux"
        } else {
            "unknown"
        }

        scrollSpeedMultiplier = if (os == "mac") {
            1.0f
        } else {
            10.0f
        }
    }

    /**
     * Returns the key mask of a given input event
     *
     * @param[e] The input event to evaluate.
     */
    private fun getMask(e: InputEvent, initial: Int = 0): Int {
        val modifiers = e.modifiers
        var mask = initial

        /*
		 * For scrolling AWT uses the SHIFT_DOWN_MASK to indicate horizontal scrolling.
		 * We keep track of whether the SHIFT key was actually pressed for disambiguation.
		 */
        if (modifiers and GLFW_MOD_SHIFT == 1)
            mask = mask or (1 shl 6)

        /*
		 * On OS X AWT sets the META_DOWN_MASK to for right clicks. We keep
		 * track of whether the META key was actually pressed for
		 * disambiguation.
		 */
        if (modifiers and GLFW_MOD_ALT == 1) {
            mask = mask or (1 shl 8)
        }

        if (modifiers and GLFW_MOD_SUPER == 1) {
            logger.warn("Windows key not supported")
        }

        /*
		 * We add the button modifiers to modifiersEx such that the
		 * XXX_DOWN_MASK can be used as the canonical flag. E.g. we adapt
		 * modifiersEx such that BUTTON1_DOWN_MASK is also present in
		 * mouseClicked() when BUTTON1 was clicked (although the button is no
		 * longer down at this point).
		 *
		 * ...but only if its not a MouseWheelEvent because OS X sets button
		 * modifiers if ALT or META modifiers are pressed.
		 */
        if (e is MouseEvent && (e.rotation[0] < 0.001f || e.rotation[1] < 0.001f)) {
            if (modifiers and InputEvent.BUTTON1_MASK != 0) {
                mask = mask or (1 shl 10)
            }
            if (modifiers and InputEvent.BUTTON2_MASK != 0) {
                mask = mask or (1 shl 11)
            }
            if (modifiers and InputEvent.BUTTON3_MASK != 0) {
                mask = mask or (1 shl 12)
            }
        }

        /*
		 * Deal with mous double-clicks.
		 */

        if (e is MouseEvent && e.clickCount > 1) {
            mask = mask or InputTrigger.DOUBLE_CLICK_MASK
        } // mouse

        if (e is MouseEvent && e.eventType == MouseEvent.EVENT_MOUSE_WHEEL_MOVED) {
            mask = mask or InputTrigger.SCROLL_MASK
            mask = mask and (1 shl 10).inv()
        }

        return mask
    }

    /**
     * Called when the mouse is moved, evaluates active drag behaviours, updates state
     *
     * @param[e] The incoming MouseEvent
     */
    fun mouseMoved(e: MouseEvent) {
        update()

        mouseX = e.x
        mouseY = e.y

        for (drag in activeKeyDrags) {
            drag.behaviour.drag(mouseX, mouseY)
        }

        for (drag in activeButtonDrags) {
            drag.behaviour.drag(mouseX, mouseY)
        }
    }

    /**
     * Called when the mouse enters, updates state
     *
     * @param[e] The incoming MouseEvent
     */
    @Suppress("UNUSED_PARAMETER")
    fun mouseEntered(e: MouseEvent) {
        update()
    }

    /**
     * Called when the mouse is clicked, updates state
     *
     * @param[e] The incoming MouseEvent
     */
    @Suppress("UNUSED_PARAMETER")
    fun mouseClicked(e: MouseEvent) {
        update()

        val mask = getMask(e)
        val x = e.x
        val y = e.y

        val clickMask = mask and InputTrigger.DOUBLE_CLICK_MASK.inv()
        for (click in buttonClicks) {
            if (click.buttons.matches(mask, pressedKeys) || clickMask != mask && click.buttons.matches(clickMask, pressedKeys)) {
                click.behaviour.click(x, y)
            }
        }
    }

    /**
     * Called when the mouse wheel is moved
     *
     * @param[e] The incoming mouse event
     */
    fun mouseWheelMoved(e: MouseEvent) {
        update()

        val mask = getMask(e)
        val x = e.x
        val y = e.y
        val wheelRotation = e.rotation

        /*
		 * AWT uses the SHIFT_DOWN_MASK to indicate horizontal scrolling. We
		 * keep track of whether the SHIFT key was actually pressed for
		 * disambiguation. However, we can only detect horizontal scrolling if
		 * the SHIFT key is not pressed. With SHIFT pressed, everything is
		 * treated as vertical scrolling.
		 */
        val exShiftMask = e.getModifiers() and InputEvent.SHIFT_MASK != 0
        val isHorizontal = !shiftPressed && exShiftMask && wheelRotation[1] == 0.0f

        for (scroll in scrolls) {
            if (scroll.buttons.matches(mask, pressedKeys)) {
                if (isHorizontal) {
                    scroll.behaviour.scroll(wheelRotation[0].toDouble(), isHorizontal, x, y)
                } else {
                    scroll.behaviour.scroll(wheelRotation[1].toDouble(), isHorizontal, x, y)
                }
            }
        }
    }

    /**
     * Called when the mouse is release
     *
     * @param[e] The incoming mouse event
     */
    fun mouseReleased(e: MouseEvent) {
        update()

        val x = e.x
        val y = e.y

        for (drag in activeButtonDrags)
            drag.behaviour.end(x, y)
        activeButtonDrags.clear()
    }

    /**
     * Called when the mouse is dragged, evaluates current drag behaviours
     *
     * @param[e] The incoming mouse event
     */
    fun mouseDragged(e: MouseEvent) {
        update()

        val x = e.x
        val y = e.y

        for (drag in activeButtonDrags) {
            drag.behaviour.drag(x, y)
        }
    }

    /**
     * Called when the mouse is exiting, updates state
     *
     * @param[e] The incoming mouse event
     */
    @Suppress("UNUSED_PARAMETER")
    fun mouseExited(e: MouseEvent) {
        update()
    }

    infix fun getMouseButton(button: Int): Int = glfwGetMouseButton(window.window, button)

    /**
     * Called when the mouse is pressed, updates state and masks, evaluates drags
     *
     * @param[e] The incoming mouse event
     */
    fun mousePressed(e: MouseEvent) {
        update()

        val mask = getMask(e, initial = 1024)
        val x = e.x
        val y = e.y

        for (drag in buttonDrags) {
            if (drag.buttons.matches(mask, pressedKeys)) {
                drag.behaviour.init(x, y)
                activeButtonDrags.add(drag)
            }
        }

        val clickMask = mask and InputTrigger.DOUBLE_CLICK_MASK.inv()
        buttonClicks
            .filter { it.buttons.matches(mask, pressedKeys) || clickMask != mask && it.buttons.matches(clickMask, pressedKeys) }
            .forEach { it.behaviour.click(x, y) }
    }

    /**
     * Called when a key is pressed
     *
     * @param[e] The incoming keyboard event
     */
    fun keyPressed(e: KeyEvent) {
        update()

        logger.trace("Key pressed: ${e.keyCode}")
        /*if (e.modifiers and GLFW_MOD_SHIFT == 1) {
            shiftPressed = true
        } else if (e.modifiers and GLFW_MOD_ALT == 1) {
            metaPressed = true
        } else if (e.modifiers and GLFW_MOD_CONTROL == 1) {
            winPressed = true
        }*/
        if (e.keyCode.toInt() != GLFW_KEY_LEFT_ALT &&
            e.keyCode.toInt() != GLFW_KEY_LEFT_CONTROL &&
            e.keyCode.toInt() != GLFW_KEY_LEFT_SHIFT) {
            val inserted = pressedKeys.add(e.keyCode.toInt())

            /*
			 * Create mask and deal with double-click on keys.
			 */

            val mask = getMask(e)
            var doubleClick = false
            if (inserted) {
                // double-click on keys.
                val lastPressTime = keyPressTimes.get(e.keyCode.toInt())
                if (lastPressTime.toInt() != -1 && e.`when` - lastPressTime < DOUBLE_CLICK_INTERVAL)
                    doubleClick = true

                keyPressTimes.put(e.keyCode.toInt(), e.`when`)
            }
            val doubleClickMask = mask or InputTrigger.DOUBLE_CLICK_MASK

            for (drag in keyDrags) {
                if (!activeKeyDrags.contains(drag) && (drag.buttons.matches(mask, pressedKeys) || doubleClick && drag.buttons.matches(doubleClickMask, pressedKeys))) {
                    drag.behaviour.init(mouseX, mouseY)
                    activeKeyDrags.add(drag)
                }
            }

            for (click in keyClicks) {
                logger.trace(click.buttons.mask.toString() + " vs " + mask.toString())
                logger.trace(click.buttons.pressedKeys.toString() + " vs " + pressedKeys.toString())
                if (click.buttons.matches(mask, pressedKeys) || doubleClick && click.buttons.matches(doubleClickMask, pressedKeys)) {
                    click.behaviour.click(mouseX, mouseY)
                }
            }
        }
    }

    /**
     * Called when a key is released
     *
     * @param[e] The incoming keyboard event
     */
    fun keyReleased(e: KeyEvent) {
        update()

        if (e.keyCode == KeyEvent.VK_SHIFT) {
            shiftPressed = false
        } else if (e.keyCode == KeyEvent.VK_META) {
            metaPressed = false
        } else if (e.keyCode == KeyEvent.VK_WINDOWS) {
            winPressed = false
        } else if (e.keyCode != KeyEvent.VK_ALT &&
            e.keyCode != KeyEvent.VK_CONTROL &&
            e.keyCode != KeyEvent.VK_ALT_GRAPH) {
            pressedKeys.remove(e.keyCode.toInt())

            for (drag in activeKeyDrags)
                drag.behaviour.end(mouseX, mouseY)
            activeKeyDrags.clear()
        }
    }

    /**
     * Called when the window lost focus. Clears pressed keys
     *
     * @param[e] The incoming window update event
     */
    @Suppress("UNUSED_PARAMETER")
    fun windowLostFocus(e: WindowEvent?) {
        pressedKeys.clear()
        shiftPressed = false
        metaPressed = false
        winPressed = false
    }

    /**
     * Called when a window regains focus, clears pressed keys
     *
     * @param[e] The incoming window update event
     */
    @Suppress("UNUSED_PARAMETER")
    fun windowGainedFocus(e: WindowEvent?) {
        pressedKeys.clear()
        shiftPressed = false
        metaPressed = false
        winPressed = false
    }

    override fun close() {
        super.close()
        cursorCallback.close()
        keyCallback.close()
        mouseCallback.close()
        scrollCallback.close()
    }

    override fun attach(hub: Hub?, window: SceneryWindow, inputMap: InputTriggerMap, behaviourMap: BehaviourMap): MouseAndKeyHandlerBase {
        val handler: MouseAndKeyHandlerBase
        when (window) {
            is SceneryWindow.GLFWWindow -> {
                this.hub = hub
                handler = this

                handler.setInputMap(inputMap)
                handler.setBehaviourMap(behaviourMap)

                glfwSetCursorPosCallback(window.window, handler.cursorCallback)
                glfwSetKeyCallback(window.window, handler.keyCallback)
                glfwSetScrollCallback(window.window, handler.scrollCallback)
                glfwSetMouseButtonCallback(window.window, handler.mouseCallback)
            }

            is SceneryWindow.HeadlessWindow -> {
                this.hub = hub
                handler = this

                handler.setInputMap(inputMap)
                handler.setBehaviourMap(behaviourMap)
            }

            else -> throw UnsupportedOperationException("Don't know how to handle window of type $window. Supported types are: ${(this.javaClass.annotations.find { it is CanHandleInputFor } as? CanHandleInputFor)?.windowTypes?.joinToString(", ")}")
        }

        return handler
    }
}
