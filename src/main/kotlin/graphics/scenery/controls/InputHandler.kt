package graphics.scenery.controls

import graphics.scenery.*
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.controls.behaviours.*
import graphics.scenery.utils.LazyLogger
import io.github.classgraph.ClassGraph
import net.java.games.input.Component
import org.scijava.ui.behaviour.Behaviour
import org.scijava.ui.behaviour.BehaviourMap
import org.scijava.ui.behaviour.InputTrigger
import org.scijava.ui.behaviour.InputTriggerMap
import org.scijava.ui.behaviour.io.InputTriggerConfig
import org.scijava.ui.behaviour.io.InputTriggerDescription
import org.scijava.ui.behaviour.io.InputTriggerDescriptionsBuilder
import org.scijava.ui.behaviour.io.gui.CommandDescriptionBuilder
import org.scijava.ui.behaviour.io.gui.VisualEditorPanel
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO
import org.scijava.ui.behaviour.util.Behaviours
import java.io.*
import javax.swing.JFrame

/**
 * Input orchestrator for ClearGL windows
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[scene] The currently displayed scene
 * @property[renderer] The active renderer
 * @property[window] The window the renderer is displaying to
 * @property[hub] [Hub] for handoing communication
 * @constructor Creates a default behaviour list and input map, also reads the configuration from a file.
 */
open class InputHandler(scene: Scene, renderer: Renderer, override var hub: Hub?, forceHandler: Class<*>? = null) : Hubable, AutoCloseable {
    /** logger for the InputHandler **/
    internal val logger by LazyLogger()

    /** ui-behaviour input trigger map, stores what actions (key presses, etc) trigger which actions. */
    internal val inputMap = InputTriggerMap()

    /** ui-behaviour behaviour map, stores the available behaviours */
    internal val behaviourMap = BehaviourMap()

    /** JOGL-flavoured ui-behaviour MouseAndKeyHandlerBase */
    val handler: MouseAndKeyHandlerBase?

    /** Scene the input handler refers to */
    internal val scene: Scene

    /** Renderer the input handler uses */
    internal val renderer: Renderer

    /** window the input handler receives input events from */
    internal val window: SceneryWindow = renderer.window

    /** configuration of the input triggers */
    internal var config: InputTriggerConfig = InputTriggerConfig()

    init {
        if (forceHandler != null) {
            handler = forceHandler.getConstructor(Hub::class.java)?.newInstance(hub) as? MouseAndKeyHandlerBase
            handler?.attach(hub, window, inputMap, behaviourMap)
        } else {
            when (window) {
                is SceneryWindow.UninitializedWindow -> {
                    logger.error("Uninitialized windows cannot have input handlers.")
                    handler = null
                }

                is SceneryWindow.HeadlessWindow -> {
                    handler = null
                }

                else -> {
                    val start = System.nanoTime()
                    val handlers = ClassGraph()
                        .acceptPackages("graphics.scenery.controls")
                        .enableClassInfo()
                        .enableAnnotationInfo()
                        .scan()
                        .getClassesWithAnnotation("graphics.scenery.controls.CanHandleInputFor")
                        .loadClasses()
                    val duration = System.nanoTime() - start

                    if (logger.isDebugEnabled) {
                        logger.debug("Found potential input handlers (${duration / 10e6} ms): ${handlers.joinToString { "${it.simpleName} -> ${it.getAnnotation(CanHandleInputFor::class.java).windowTypes.joinToString()}" }}")
                    }
                    val candidate = handlers.find { it.getAnnotation(CanHandleInputFor::class.java).windowTypes.contains(window::class) }
                    handler = candidate?.getConstructor(Hub::class.java)?.newInstance(hub) as MouseAndKeyHandlerBase?
                    handler?.attach(hub, window, inputMap, behaviourMap)
                }
            }
        }

        this.scene = scene
        this.renderer = renderer

        hub?.add(SceneryElement.Input, this)
    }

    /**
     * Adds a behaviour to the map of behaviours, making them available for key bindings
     *
     * @param[behaviourName] The name of the behaviour
     * @param[behaviour] The behaviour to add.
     */
    fun addBehaviour(behaviourName: String, behaviour: Behaviour) {
        behaviourMap.put(behaviourName, behaviour)
    }

    /**
     * Removes a behaviour from the map of behaviours.
     *
     * @param[behaviourName] The name of the behaviour to remove.
     */
    fun removeBehaviour(behaviourName: String) {
        behaviourMap.remove(behaviourName)
    }

    /**
     * Returns *a copy of* the behaviours currently
     * registered with this {@link InputHandler}.
     */
    fun getAllBehaviours(): List<String> {
        return behaviourMap.keys().sorted()
        //NB: behaviourMap.keys() returns a copy of its keys
    }

    /**
     * Adds a key binding for a given behaviour
     *
     * @param[behaviourName] The behaviour to add a key binding for
     * @param[keys] Which keys should trigger this behaviour?
     */
    fun addKeyBinding(behaviourName: String, vararg keys: String) {
        keys.forEach { key ->
            val trigger = InputTrigger.getFromString(key)
            inputMap.put(trigger, behaviourName)
            config.add(trigger, behaviourName, "all")
        }
    }

    /**
     * Returns *a copy of* all keys {@link InputTrigger}s associated
     * with the given behaviour
     */
    fun getKeyBindings(behaviourName: String): Set<InputTrigger> {
        return config.getInputs(behaviourName, "all")
        //NB: this assumes that 'config' and 'inputMap' are well synchronized,
        //    otherwise we would have to read 'inputMap' and build the set ourselves
    }

    /**
     * Returns *a copy of* all the currently set key bindings
     *
     * @return Map of all currently configured key bindings.
     */
    fun getAllBindings(): Map<InputTrigger, Set<String>> {
        return inputMap.allBindings
    }

    /**
     * Removes a key binding for a given behaviour
     *
     * @param[behaviourName] The behaviour to remove the key binding for.
     */
    fun removeKeyBinding(behaviourName: String) {
        config.getInputs(behaviourName, "all").forEach { inputTrigger ->
            inputMap.remove(inputTrigger, behaviourName)
            config.remove(inputTrigger, behaviourName, "all")
        }
    }

    /**
     * Returns the behaviour with the given name, if it exists. Otherwise null is returned.
     *
     * @param[behaviourName] The name of the behaviour
     */
    fun getBehaviour(behaviourName: String): Behaviour? {
        return behaviourMap.get(behaviourName)
    }

    /**
     * Reads a default list of key bindings from a file, and sets sane
     * defaults for those not set by the config
     *
     * @param[bindingConfigFile] The filename to read the configuration from.
     */
    fun useDefaultBindings(bindingConfigFile: String) {
        // Load YAML config
        var reader: Reader

        try {
            reader = FileReader(bindingConfigFile)
        } catch (e: FileNotFoundException) {
            logger.info("No custom key configuration found, using default keybindings.")
            reader = StringReader("---\n" +
                "- !mapping" + "\n" +
                "  action: mouse_control" + "\n" +
                "  contexts: [all]" + "\n" +
                "  triggers: [button1, M]" + "\n" +
                "- !mapping" + "\n" +
                "  action: gamepad_movement_control" + "\n" +
                "  contexts: [all]" + "\n" +
                "  triggers: [button1]" + "\n" +
                "- !mapping" + "\n" +
                "  action: gamepad_camera_control" + "\n" +
                "  contexts: [all]" + "\n" +
                "  triggers: [G]" + "\n" +
                "- !mapping" + "\n" +
                "  action: scroll1" + "\n" +
                "  contexts: [all]" + "\n" +
                "  triggers: [scroll]" + "\n" +
                "")
        }

        config = InputTriggerConfig(YamlConfigIO.read(reader))

        val settings = hub?.get(SceneryElement.Settings) as? Settings
        val slowMovementSpeed: Float = settings?.get("Input.SlowMovementSpeed", 0.5f) ?: 0.5f
        val fastMovementSpeed: Float = settings?.get("Input.FastMovementSpeed", 1.0f) ?: 1.0f
        /*
     * Create behaviours and input mappings.
     */
        behaviourMap.put("mouse_control", FPSCameraControl({ scene.findObserver() }, window.width, window.height))
        behaviourMap.put("gamepad_camera_control", GamepadRotationControl(listOf(Component.Identifier.Axis.Z, Component.Identifier.Axis.RZ)) { scene.findObserver() })
        behaviourMap.put("gamepad_movement_control", GamepadMovementControl(listOf(Component.Identifier.Axis.X, Component.Identifier.Axis.Y)) { scene.findObserver() })

        //unused until some reasonable action (to the selection) would be provided
        //behaviourMap.put("select_command", SelectCommand("select_command", renderer, scene, { scene.findObserver() }))

        behaviourMap.put("move_forward", MovementCommand("forward", { scene.findObserver() }, slowMovementSpeed))
        behaviourMap.put("move_back", MovementCommand("back", { scene.findObserver() }, slowMovementSpeed))
        behaviourMap.put("move_left", MovementCommand("left", { scene.findObserver() }, slowMovementSpeed))
        behaviourMap.put("move_right", MovementCommand("right", { scene.findObserver() }, slowMovementSpeed))
        behaviourMap.put("move_up", MovementCommand("up", { scene.findObserver() }, slowMovementSpeed))
        behaviourMap.put("move_down", MovementCommand("down", { scene.findObserver() }, slowMovementSpeed))

        behaviourMap.put("move_forward_fast", MovementCommand("forward", { scene.findObserver() }, fastMovementSpeed))
        behaviourMap.put("move_back_fast", MovementCommand("back", { scene.findObserver() }, fastMovementSpeed))
        behaviourMap.put("move_left_fast", MovementCommand("left", { scene.findObserver() }, fastMovementSpeed))
        behaviourMap.put("move_right_fast", MovementCommand("right", { scene.findObserver() }, fastMovementSpeed))
        behaviourMap.put("move_up_fast", MovementCommand("up", { scene.findObserver() }, fastMovementSpeed))
        behaviourMap.put("move_down_fast", MovementCommand("down", { scene.findObserver() }, fastMovementSpeed))

        behaviourMap.put("toggle_debug", ToggleCommand(renderer, "toggleDebug"))
        behaviourMap.put("toggle_fullscreen", ToggleCommand(renderer, "toggleFullscreen"))
        behaviourMap.put("screenshot", ToggleCommand(renderer, "screenshot"))
        behaviourMap.put("set_rendering_quality", EnumCycleCommand(RenderConfigReader.RenderingQuality::class.java, renderer, "setRenderingQuality"))
        behaviourMap.put("record_movie", ToggleCommand(renderer, "recordMovie"))

        behaviourMap.put("toggle_vr", ToggleCommand(renderer, "toggleVR"))

        val adder = config.inputTriggerAdder(inputMap, "all")
        adder.put("mouse_control") // put input trigger as defined in config
        adder.put("gamepad_movement_control")
        adder.put("gamepad_camera_control")

        //adder.put("select_command", "double-click button1")

        adder.put("move_forward", "W")
        adder.put("move_left", "A")
        adder.put("move_back", "S")
        adder.put("move_right", "D")

        adder.put("move_forward_fast", "shift W")
        adder.put("move_left_fast", "shift A")
        adder.put("move_back_fast", "shift S")
        adder.put("move_right_fast", "shift D")

        adder.put("move_up", "K")
        adder.put("move_down", "J")

        adder.put("set_rendering_quality", "Q")
        adder.put("toggle_debug", "shift Q")
        adder.put("toggle_fullscreen", "F")

        adder.put("screenshot", "P")
        adder.put("record_movie", "shift P")

        adder.put("toggle_vr", "shift V")
    }

    override fun close() {
        logger.debug("Closing InputHandler")
        handler?.close()
    }

    /**
     * Returns a list of [InputTriggerDescription] for the current input handler.
     * Used for serialisation.
     */
    fun getDescriptions(context: String): MutableList<InputTriggerDescription>? {
        val builder = InputTriggerDescriptionsBuilder()

        builder.addMap(inputMap, context)
//        builder.addMap(viewerFrame.getTriggerbindings().getConcatenatedInputTriggerMap(), context)

        return builder.descriptions
    }

    /**
     * Reads keybindings from a [file], overwriting the current ones.
     */
    fun readFromFile(file: File) {
        try {
            val reader = FileReader(file)
            config.set(InputTriggerConfig(YamlConfigIO.read(reader)))

            logger.info("Read input configuration from $file")
        } catch (e: IOException) {
            logger.error("Could not read input config from $file: $e")
        }
    }

    /**
     * Opens a key binding editor panel with [editorTitle] as title.
     *
     * If the configuration is updated, keybindings from a given [context]
     * are written to a file with a given [filename] in the user's home directory.
     */
    @JvmOverloads fun openKeybindingsGuiEditor(editorTitle: String = "scenery's Key bindings editor", filename: String, context: String = "all"): VisualEditorPanel {
        //setup content for the Visual Editor
        val cdb = CommandDescriptionBuilder()
        behaviourMap.keys().forEach { b -> cdb.addCommand(b, context, "") }
        val editorPanel = VisualEditorPanel(config, cdb.get())

        //show the Editor
        val frame = JFrame(editorTitle)
        frame.contentPane.add(editorPanel)
        frame.pack()
        frame.isVisible = true

        //process "Apply" button of the editor
        editorPanel.configCommittedListeners().add(
            VisualEditorPanel.ConfigChangeListener {
                Behaviours(inputMap, behaviourMap, config, "all").updateKeyConfig(config)

                val outputFile = File(System.getProperty("user.home")).resolve(filename)
                try {
                    val writer = FileWriter(outputFile)
                    YamlConfigIO.write(getDescriptions(context), writer)
                    writer.close()
                } catch (e: IOException) {
                    logger.error("Could not write key bindings to $outputFile: $e")
                }
            })

        //return reference on the Editor, so that users can hook own extra stuff
        return editorPanel
    }
    
    operator fun plusAssign(behaviourAndBinding: NamedBehaviourWithKeyBinding) {
        addBehaviour(behaviourAndBinding.name, behaviourAndBinding.behaviour)
        addKeyBinding(behaviourAndBinding.name, behaviourAndBinding.key)
    }

    operator fun minusAssign(name: String) {
        removeBehaviour(name)
        removeKeyBinding(name)
    }

    data class NamedBehaviourWithKeyBinding(val name: String, val behaviour: Behaviour, val key: String)

}
