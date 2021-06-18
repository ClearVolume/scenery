package graphics.scenery.controls

import gnu.trove.map.hash.TIntLongHashMap
import gnu.trove.set.hash.TIntHashSet
import graphics.scenery.Hub
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.controls.behaviours.GamepadBehaviour
import graphics.scenery.controls.behaviours.GamepadClickBehaviour
import graphics.scenery.utils.ExtractsNatives
import graphics.scenery.utils.ExtractsNatives.Platform.*
import graphics.scenery.utils.LazyLogger
import net.java.games.input.*
import org.scijava.ui.behaviour.*
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Base class for MouseAndKeyHandlers
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class MouseAndKeyHandlerBase : ControllerListener, ExtractsNatives {
    protected val logger by LazyLogger()
    /** ui-behaviour input trigger map */
    protected lateinit var inputTriggerMap: InputTriggerMap

    /** ui-behaviour behaviour map */
    protected lateinit var behaviours: BehaviourMap

    /** expected modifier count */
    protected var inputMapExpectedModCount: Int = 0

    /** behaviour expected modifier count */
    protected var behaviourMapExpectedModCount: Int = 0

    /** handle to the active controller */
    protected var controller: Controller? = null

    private var controllerThread: Thread? = null
    private var controllerAxisDown: ConcurrentHashMap<Component.Identifier, Float> = ConcurrentHashMap()
    private val gamepads = CopyOnWriteArrayList<BehaviourEntry<Behaviour>>()
    private val CONTROLLER_HEARTBEAT = 5L
    private val CONTROLLER_DOWN_THRESHOLD = 0.5f

    protected var shouldClose = false

    /**
     * Managing internal behaviour lists.
     *
     * The internal lists only contain entries for Behaviours that can be
     * actually triggered with the current InputMap, grouped by Behaviour type,
     * such that hopefully lookup from the event handlers is fast,
     *
     * @property[buttons] Buttons triggering the input
     * @property[behaviour] Behaviour triggered by these buttons
     */
    class BehaviourEntry<out T : Behaviour>(
        val buttons: InputTrigger,
        val behaviour: T)

    protected val buttonDrags = ArrayList<BehaviourEntry<DragBehaviour>>()

    protected val keyDrags = ArrayList<BehaviourEntry<DragBehaviour>>()

    protected val buttonClicks = ArrayList<BehaviourEntry<ClickBehaviour>>()

    protected val keyClicks = ArrayList<BehaviourEntry<ClickBehaviour>>()

    protected val scrolls = ArrayList<BehaviourEntry<ScrollBehaviour>>()


    /**
     * Which keys are currently pressed. This does not include modifier keys
     * Control, Shift, Alt, AltGr, Meta.
     */
    protected val pressedKeys = TIntHashSet(5, 0.5f, -1)

    /**
     * When keys where pressed
     */
    protected val keyPressTimes = TIntLongHashMap(100, 0.5f, -1, -1)

    /**
     * Whether the SHIFT key is currently pressed. We need this, because for
     * mouse-wheel AWT uses the SHIFT_DOWN_MASK to indicate horizontal
     * scrolling. We keep track of whether the SHIFT key was actually pressed
     * for disambiguation.
     */
    protected var shiftPressed = false

    /**
     * Whether the META key is currently pressed. We need this, because on OS X
     * AWT sets the META_DOWN_MASK to for right clicks. We keep track of whether
     * the META key was actually pressed for disambiguation.
     */
    protected var metaPressed = false

    /**
     * Whether the WINDOWS key is currently pressed.
     */
    protected var winPressed = false

    /**
     * The current mouse coordinates, updated through [.mouseMoved].
     */
    protected var mouseX: Int = 0

    /**
     * The current mouse coordinates, updated through [.mouseMoved].
     */
    protected var mouseY: Int = 0

    /**
     * Active [DragBehaviour]s initiated by mouse button press.
     */
    protected val activeButtonDrags = ArrayList<BehaviourEntry<DragBehaviour>>()

    /**
     * Active [DragBehaviour]s initiated by key press.
     */
    protected val activeKeyDrags = ArrayList<BehaviourEntry<DragBehaviour>>()

    /** the windowing system's set double click interval */
    protected open val DOUBLE_CLICK_INTERVAL = getDoubleClickInterval()

    init {
        java.util.logging.Logger.getLogger(ControllerEnvironment::class.java.name).parent.level = Level.SEVERE

        /** Returns the name of the DLL/so/dylib required by JInput on the given platform. */
        fun ExtractsNatives.Platform.getPlatformJinputLibraryName(): String {
            return when(this) {
                WINDOWS -> "jinput-raw_64.dll"
                LINUX -> "libjinput-linux64.so"
                MACOS -> "libjinput-osx.jnilib"
                UNKNOWN -> "none"
            }
        }

        try {
            val platformJars = getNativeJars("jinput-platform", hint = ExtractsNatives.getPlatform().getPlatformJinputLibraryName())
            logger.debug("Native JARs for JInput: ${platformJars.joinToString(", ")}")
            val path = extractLibrariesFromJar(platformJars, load = false)
            System.setProperty("net.java.games.input.librarypath", path)

            ControllerEnvironment.getDefaultEnvironment().controllers.forEach {
                if (it.type == Controller.Type.STICK || it.type == Controller.Type.GAMEPAD) {
                    this.controller = it
                    logger.info("Added gamepad controller: $it")
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not initialize JInput: ${e.message}")
            logger.debug("Traceback: ${e.stackTrace}")
        }

        controllerThread = thread {
            var queue: EventQueue
            val event = Event()

            while (!shouldClose) {
                controller?.let { c ->
                    c.poll()

                    queue = c.eventQueue

                    while (queue.getNextEvent(event)) {
                        controllerEvent(event)
                    }
                }

                gamepads.forEach { gamepad ->
                    for (it in controllerAxisDown) {
                        val b = gamepad.behaviour
                        if (b is GamepadBehaviour) {
                            if (abs(it.value) > 0.02f && b.axis.contains(it.key)) {
                                logger.trace("Triggering ${it.key} because axis is down (${it.value})")
                                b.axisEvent(it.key, it.value)
                            }
                        }
                    }
                }

                Thread.sleep(this.CONTROLLER_HEARTBEAT)
            }
        }
    }

    /**
     * Queries the windowing system for the current double click interval
     *
     * @return The double click interval in ms
     */
    internal open fun getDoubleClickInterval(): Int {
        val prop = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval")

        return if (prop == null) {
            200
        } else {
            prop as? Int ?: 200
        }
    }

    /**
     * Sets the input trigger map to the given map
     *
     * @param[inputMap] The input map to set
     */
    fun setInputMap(inputMap: InputTriggerMap) {
        this.inputTriggerMap = inputMap
        inputMapExpectedModCount = inputMap.modCount() - 1
    }

    /**
     * Sets the behaviour trigger map to the given map
     *
     * @param[behaviourMap] The behaviour map to set
     */
    fun setBehaviourMap(behaviourMap: BehaviourMap) {
        this.behaviours = behaviourMap
        behaviourMapExpectedModCount = behaviourMap.modCount() - 1
    }

    /**
     * Make sure that the internal behaviour lists are up to date. For this, we
     * keep track the modification count of [.inputMap] and
     * [.behaviourMap]. If expected mod counts are not matched, call
     * [.updateInternalMaps] to rebuild the internal behaviour lists.
     */
    @Synchronized protected fun update() {
        val imc = inputTriggerMap.modCount()
        val bmc = behaviours.modCount()

        if (imc != inputMapExpectedModCount || bmc != behaviourMapExpectedModCount) {
            inputMapExpectedModCount = imc
            behaviourMapExpectedModCount = bmc
            updateInternalMaps()
        }
    }

    /**
     * Build internal lists buttonDrag, keyDrags, etc from BehaviourMap(?) and
     * InputMap(?). The internal lists only contain entries for Behaviours that
     * can be actually triggered with the current InputMap, grouped by Behaviour
     * type, such that hopefully lookup from the event handlers is fast.
     */
    private fun updateInternalMaps() {
        buttonDrags.clear()
        keyDrags.clear()
        buttonClicks.clear()
        keyClicks.clear()

        for ((buttons, values) in inputTriggerMap.allBindings) {
            val behaviourKeys = values ?: continue

            for (behaviourKey in behaviourKeys) {
                val behaviour = behaviours.get(behaviourKey) ?: continue

                if (behaviour is DragBehaviour) {
                    val dragEntry = BehaviourEntry(buttons, behaviour)
                    if (buttons.isKeyTriggered)
                        keyDrags.add(dragEntry)
                    else
                        buttonDrags.add(dragEntry)
                }

                if (behaviour is ClickBehaviour) {
                    val clickEntry = BehaviourEntry(buttons, behaviour)
                    if (buttons.isKeyTriggered)
                        keyClicks.add(clickEntry)
                    else
                        buttonClicks.add(clickEntry)
                }

                if (behaviour is ScrollBehaviour) {
                    val scrollEntry = BehaviourEntry(buttons, behaviour)
                    scrolls.add(scrollEntry)
                }

                if (behaviour is GamepadBehaviour || behaviour is GamepadClickBehaviour) {
                    val gamepadEntry = BehaviourEntry(buttons, behaviour)
                    gamepads.add(gamepadEntry)
                }
            }
        }
    }

    /**
     * Called when a new controller is added
     *
     * @param[event] The incoming controller event
     */
    override fun controllerAdded(event: ControllerEvent?) {
        if (controller == null && event != null && event.controller.type == Controller.Type.GAMEPAD) {
            logger.info("Adding controller ${event.controller}")
            this.controller = event.controller
        }
    }

    /**
     * Called when a controller is removed
     *
     * @param[event] The incoming controller event
     */
    override fun controllerRemoved(event: ControllerEvent?) {
        if (event != null && controller != null) {
            logger.info("Controller removed: ${event.controller}")

            controller = null
        }
    }

    private fun controllerButtonToKeyCode(id: Component.Identifier, value: Float): Int? {
        return when(id.name) {
            GamepadButton.Button0.ordinal.toString() -> KeyEvent.VK_0
            GamepadButton.Button1.ordinal.toString()-> KeyEvent.VK_1
            GamepadButton.Button2.ordinal.toString() -> KeyEvent.VK_2
            GamepadButton.Button3.ordinal.toString() -> KeyEvent.VK_3
            GamepadButton.Button4.ordinal.toString() -> KeyEvent.VK_4
            GamepadButton.Button5.ordinal.toString() -> KeyEvent.VK_5
            GamepadButton.Button6.ordinal.toString() -> KeyEvent.VK_6
            GamepadButton.Button7.ordinal.toString() -> KeyEvent.VK_7
            GamepadButton.Button8.ordinal.toString() -> KeyEvent.VK_8

            "pov" -> {
                when (value) {
                    0.25f -> KeyEvent.VK_NUMPAD8
                    0.5f -> KeyEvent.VK_NUMPAD6
                    0.75f -> KeyEvent.VK_NUMPAD2
                    1.0f -> KeyEvent.VK_NUMPAD4
                    else -> null
                }
            }
            else -> null
        }
    }

    private val pressedGamepadKeys = TIntHashSet()

    /**
     * Called when a controller event is fired. This will update the currently down
     * buttons/axis on the controller.
     *
     * @param[event] The incoming controller event
     */
    fun controllerEvent(event: Event) {
        logger.trace("Event: $event/identifier=${event.component.identifier}")
        for (gamepad in gamepads) {
            if (event.component.isAnalog) {
                if (abs(event.component.pollData) < CONTROLLER_DOWN_THRESHOLD) {
                    logger.trace("${event.component.identifier} over threshold, removing")
                    controllerAxisDown[event.component.identifier] = 0.0f
                } else {
                    controllerAxisDown[event.component.identifier] = event.component.pollData
                }
            } else {
                val button = controllerButtonToKeyCode(event.component.identifier, event.value)

                if (event.component.identifier != Component.Identifier.Axis.POV && button != null) {
                    if (event.value < 0.1f) {
                        pressedGamepadKeys.remove(button)
                    }
                    if (event.value > 0.9f) {
                        pressedGamepadKeys.add(button)
                    }
                } else {
                    if(button == null) {
                        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { value ->
                            controllerButtonToKeyCode(Component.Identifier.Axis.POV, value)?.let {
                                pressedGamepadKeys.remove(it)
                            }
                        }
                    } else {
                        if (event.value > 0.05f) {
                            pressedGamepadKeys.add(button)
                        } else {
                            pressedGamepadKeys.remove(button)
                        }
                    }
                }
            }

            when(val b = gamepad.behaviour) {
                is GamepadBehaviour -> {
                    if (b.axis.contains(event.component.identifier)) {
                        b.axisEvent(event.component.identifier, event.component.pollData)
                    }
                }

                is GamepadClickBehaviour -> {
                    if(gamepad.buttons.matches(0, pressedGamepadKeys)) {
                        b.click(0, 0)
                    }
                }
            }
        }
    }

    /**
     * Attaches this handler to a given [window], with input bindings and behaviours given in [inputMap] and
     * [behaviourMap]. MouseAndKeyHandlerBase itself cannot be attached to any windows.
     */
    open fun attach(hub: Hub?, window: SceneryWindow, inputMap: InputTriggerMap, behaviourMap: BehaviourMap): MouseAndKeyHandlerBase {
        throw UnsupportedOperationException("MouseAndKeyHandlerBase cannot be attached to a window.")
    }

    /**
     * Closes this instance of MouseAndKeyHandlerBase.
     * This function needs to be called as super.close() by derived classes in order to clean up gamepad handling logic.
     */
    open fun close() {
        shouldClose = true
        controllerThread?.join()
        controllerThread = null
        logger.debug("MouseAndKeyHandlerBase closed.")
    }
}
