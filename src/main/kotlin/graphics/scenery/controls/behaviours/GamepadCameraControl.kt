package graphics.scenery.controls.behaviours

import cleargl.GLVector
import graphics.scenery.Camera
import net.java.games.input.Component
import java.util.function.Supplier
import kotlin.reflect.KProperty

/**
 * Implementation of GamepadBehaviour for Camera Control
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[name] Name of the behaviour
 * @property[axis] List of axis that are assigned to this behaviour
 * @property[node] The camera to control
 * @property[w] The window width
 * @property[h] The window height
 */
open class GamepadCameraControl(private val name: String,
                                override val axis: List<Component.Identifier.Axis>,
                                private val n: () -> Camera?, private val w: Int, private val h: Int) : GamepadBehaviour {
    private var lastX: Float = 0.0f
    private var lastY: Float = 0.0f
    private var firstEntered = true

    /** The [graphics.scenery.Node] this behaviour class controls */
    protected var node: Camera? by CameraDelegate()

    protected inner class CameraDelegate {
        /** Returns the [graphics.scenery.Node] resulting from the evaluation of [n] */
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Camera? {
            return n.invoke()
        }

        /** Setting the value is not supported */
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Camera?) {
            throw UnsupportedOperationException()
        }
    }

    /** Pitch angle calculated from the axis position */
    private var pitch: Float = 0.0f
    /** Yaw angle calculated from the axis position */
    private var yaw: Float = 0.0f

    /** Threshold below which the behaviour will not trigger */
    var threshold = 0.1f

    /**
     * Gamepad camera control, supplying a Camera via a Java [Supplier] lambda.
     */
    @Suppress("unused")
    constructor(name: String, axis: List<Component.Identifier.Axis>, n: Supplier<Camera?>, w: Int, h: Int) : this(name, axis, { n.get() }, w, h)

    /**
     * This function is trigger upon arrival of an axis event that
     * concerns this behaviour. It takes the event's value, as well as the
     * other axis' state to construct pitch and yaw angles and reorients
     * the camera.
     *
     * @param[axis] The gamepad axis.
     * @param[value] The absolute value of the gamepad axis.
     */
    @Synchronized
    override fun axisEvent(axis: Component.Identifier, value: Float) {
        if (Math.abs(value) < threshold) {
            return
        }

        val x: Float
        val y: Float

        if (axis == this.axis.first()) {
            x = value
            y = lastY
        } else {
            x = lastX
            y = value
        }

        if (firstEntered) {
            lastX = x
            lastY = y
            firstEntered = false
        }

        var xoffset: Float = (x - lastX)
        var yoffset: Float = (lastY - y)

        lastX = x
        lastY = y

        xoffset *= 60f
        yoffset *= 60f

        yaw += xoffset
        pitch += yoffset

        if (pitch > 89.0f) {
            pitch = 89.0f
        }
        if (pitch < -89.0f) {
            pitch = -89.0f
        }

        val forward = GLVector(
            Math.cos(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat(),
            Math.sin(Math.toRadians(pitch.toDouble())).toFloat(),
            Math.sin(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat())

        node?.forward = forward.normalized
    }
}
