package graphics.scenery.controls.behaviours

import org.joml.Vector3f
import graphics.scenery.Camera
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.joml.Quaternionf
import org.scijava.ui.behaviour.DragBehaviour
import org.scijava.ui.behaviour.ScrollBehaviour
import java.util.function.Supplier

/**
 * Targeted ArcBall control
 *
 * This Behaviour provides ArcBall control for scenery, with a customizable target. If you
 * activate this behaviour, it'll use the current camera distance to the target as initial distance.
 *
 * The Targeted ArcBall also provides [minimumDistance] and [maximumDistance] to clamp the distance
 * to the target to this range.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[name] The name of the behaviour
 * @property[cam] The node this behaviour controls
 * @property[w] Window width
 * @property[h] Window height
 * @property[target] [Vector3f]-supplying with the look-at target of the arcball
 * @constructor Creates a new ArcballCameraControl behaviour
 */
open class ArcballCameraControl(private val name: String, camera: () -> Camera?, private val w: Int, private val h: Int, var target: () -> Vector3f) : DragBehaviour, ScrollBehaviour,
    WithCameraDelegateBase(camera) {
    private val logger by LazyLogger()
    private var lastX = w / 2
    private var lastY = h / 2

    /** distance to target */
    var distance: Float = 5.0f
        set(value) {
            field = value

            cam?.let { node -> node.spatialOrNull()?.position = target.invoke() + node.forward * value * (-1.0f) }
        }

    /** multiplier for zooming in and out */
    var scrollSpeedMultiplier = 0.005f
    /** multiplier for mouse movement */
    var mouseSpeedMultiplier = 0.1f
    /** minimum distance value to target */
    var minimumDistance = 0.0001f
    /** maximum distance value to target */
    var maximumDistance = Float.MAX_VALUE

    /**
     * Arcball camera control, supplying a Camera via a Java [Supplier] lambda.
     */
    @Suppress("unused")
    constructor(name: String, n: Supplier<Camera?>, w: Int, h: Int, target: Supplier<Vector3f>) : this(name, { n.get() }, w, h, { target.get() })

    /**
     * Arcball camera control, supplying a Camera via a Java [Supplier] lambda.
     * In this version, [target] is a static [Vector3f].
     */
    @Suppress("unused")
    constructor(name: String, n: () -> Camera?, w: Int, h: Int, target: Vector3f) : this(name, n, w, h, { target })

    /**
     * Arcball camera control, supplying a Camera via a Java [Supplier] lambda.
     * In this version, [target] is a static [Vector3f].
     */
    @Suppress("unused")
    constructor(name: String, n: Supplier<Camera?>, w: Int, h: Int, target: Vector3f) : this(name, { n.get() }, w, h, { target })

    /**
     * This function is called upon mouse down and initialises the camera control
     * with the current window size.
     *
     * @param[x] x position in window
     * @param[y] y position in window
     */
    override fun init(x: Int, y: Int) {
        lastX = x
        lastY = y

        cam?.targeted = true
        cam?.target = target.invoke()
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
    override fun drag(x: Int, y: Int) {
        cam?.let { node ->
            if (!node.lock.tryLock()) {
                return
            }

            val xoffset: Float = (x - lastX).toFloat() * mouseSpeedMultiplier
            val yoffset: Float = (lastY - y).toFloat() * mouseSpeedMultiplier

            lastX = x
            lastY = y

            val frameYaw = (xoffset) / 180.0f * Math.PI.toFloat()
            val framePitch = yoffset / 180.0f * Math.PI.toFloat()

            // first calculate the total rotation quaternion to be applied to the camera
            val yawQ = Quaternionf().rotateXYZ(0.0f, frameYaw, 0.0f).normalize()
            val pitchQ = Quaternionf().rotateXYZ(framePitch, 0.0f, 0.0f).normalize()

            node.ifSpatial {
                distance = (target.invoke() - position).length()
                node.target = target.invoke()
                rotation = pitchQ.mul(rotation).mul(yawQ).normalize()
                position = target.invoke() + node.forward * distance * (-1.0f)
            }

            node.lock.unlock()
        }
    }

    /**
     * The scroll function is called when a scroll event is detected and will change
     * the [distance] according to the scroll direction and bound by the [minimumDistance] and
     * [maximumDistance] values.
     *
     * @param[wheelRotation] Absolute rotation value of the mouse wheel
     * @param[isHorizontal] Whether the scroll event is horizontal. We use only vertical events.
     * @param [x] unused
     * @param[y] unused
     */
    override fun scroll(wheelRotation: Double, isHorizontal: Boolean, x: Int, y: Int) {
        if (isHorizontal || cam == null) {
            return
        }

        distance = (target.invoke() - cam!!.spatial().position).length()
        distance += wheelRotation.toFloat() * scrollSpeedMultiplier

        if (distance >= maximumDistance) distance = maximumDistance
        if (distance <= minimumDistance) distance = minimumDistance

        cam?.let { node -> node.spatialOrNull()?.position = target.invoke() + node.forward * distance * (-1.0f) }
    }

}
