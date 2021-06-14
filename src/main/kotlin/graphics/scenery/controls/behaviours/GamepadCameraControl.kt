package graphics.scenery.controls.behaviours

import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.utils.LazyLogger
import net.java.games.input.Component
import org.joml.Quaternionf
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
                           private val n: () -> Node?) : GamepadBehaviour {
    private var lastX: Float = 0.0f
    private var lastY: Float = 0.0f
    private var firstEntered = true
    private val logger by LazyLogger()

    /** The [graphics.scenery.Node] this behaviour class controls */
    protected var node: Node? by NodeDelegate()

    protected inner class NodeDelegate {
        /** Returns the [graphics.scenery.Node] resulting from the evaluation of [n] */
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Node? {
            return n.invoke()
        }

        /** Setting the value is not supported */
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Node?) {
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
        val n = node ?: return

        if(Math.abs(value) < threshold) {
            return
        }
        
        val x: Float
        val y: Float

        if(axis == this.axis.first()) {
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

//        val forward = Vector3f(
//                Math.cos(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat(),
//                Math.sin(Math.toRadians(pitch.toDouble())).toFloat(),
//                Math.sin(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat())

//        node?.forward = forward.normalized
        logger.trace("Pitch={} Yaw={}", pitch, yaw)

        n.ifSpatial {
            val yawQ = Quaternionf().rotateXYZ(0.0f, yaw, 0.0f)
            val pitchQ = Quaternionf().rotateXYZ(pitch, 0.0f, 0.0f)
            rotation = pitchQ.mul(rotation).mul(yawQ).normalize()
        }
    }
}
