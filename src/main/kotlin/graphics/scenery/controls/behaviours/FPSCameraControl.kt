package graphics.scenery.controls.behaviours

import graphics.scenery.Camera
import org.joml.Quaternionf
import org.scijava.ui.behaviour.DragBehaviour
import java.util.function.Supplier
import kotlin.reflect.KProperty

/**
 * FPS-style camera control
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[name] The name of the behaviour
 * @property[node] The node this behaviour controls
 * @property[w] Window width
 * @property[h] Window height
 * @constructor Creates a new FPSCameraControl behaviour
 */
open class FPSCameraControl(private val name: String, private val n: () -> Camera?, private val w: Int, private val h: Int) : DragBehaviour {
    private var lastX = w / 2
    private var lastY = h / 2

    /** The [graphics.scenery.Node] this behaviour class controls */
    protected var node: Camera? by CameraDelegate()

    inner class CameraDelegate {
        /** Returns the [graphics.scenery.Node] resulting from the evaluation of [n] */
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Camera? {
            return n.invoke()
        }

        /** Setting the value is not supported */
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Camera?) {
            throw UnsupportedOperationException()
        }
    }

    init {
        node?.targeted = false
    }

    /** multiplier for mouse movement */
    var mouseSpeedMultiplier = 0.1f

    /**
     * FPS-style camera control, supplying a Camera via a Java [Supplier] lambda.
     */
    @Suppress("unused")
    constructor(name: String, n: Supplier<Camera?>, w: Int, h: Int) : this(name, { n.get() }, w, h)

    /**
     * This function is called upon mouse down and initialises the camera control
     * with the current window size.
     *
     * @param[x] x position in window
     * @param[y] y position in window
     */
    override fun init(x: Int, y: Int) {
        node?.targeted = false
        lastX = x
        lastY = y
    }

    /**
     * This function is called upon mouse down ends.
     *
     * @param[x] x position in window
     * @param[y] y position in window
     */
    override fun end(x: Int, y: Int) {
    }

    /**
     * This function is called during mouse down and updates the yaw and pitch states,
     * and resets the cam's forward and up vectors according to these angles.
     *
     * @param[x] x position in window
     * @param[y] y position in window
     */
    @Synchronized override fun drag(x: Int, y: Int) {
        if(node?.lock?.tryLock() != true) {
            return
        }

        node?.ifSpatial {

            var xoffset: Float = (x - lastX).toFloat() * mouseSpeedMultiplier
            var yoffset: Float = (y - lastY).toFloat() * mouseSpeedMultiplier

            lastX = x
            lastY = y

            val frameYaw = xoffset
            val framePitch = yoffset

            val yawQ = Quaternionf().rotateXYZ(0.0f, frameYaw/180.0f*Math.PI.toFloat(), 0.0f)
            val pitchQ = Quaternionf().rotateXYZ(framePitch/180.0f*Math.PI.toFloat(), 0.0f, 0.0f)
            rotation = pitchQ.mul(rotation).mul(yawQ).normalize()
        }


        node?.lock?.unlock()
    }
}
