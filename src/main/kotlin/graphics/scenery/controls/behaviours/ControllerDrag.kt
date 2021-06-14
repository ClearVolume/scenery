package graphics.scenery.controls.behaviours

import org.joml.Vector3f
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.Node
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerInput
import graphics.scenery.controls.TrackerRole
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import org.joml.Quaternionf
import org.scijava.ui.behaviour.ClickBehaviour

/**
 * Dragging behaviour for a controller of given [handedness], which is attached to an [hmd].
 * A lambda [draggedObjectFinder] can be given to select for the objects affected by the drag.
 * Inherits from [ClickBehaviour].
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
open class ControllerDrag(val handedness: TrackerRole,
                          val hmd: TrackerInput,
                          val trackPosition: Boolean = true,
                          val trackRotation: Boolean = false,
                          val draggedObjectFinder: () -> Node?): ClickBehaviour {
    private val logger by LazyLogger()
    protected var lastPosition: Vector3f? = null
    protected var lastRotation: Quaternionf? = null
    // half a second of timeout
    var timeout = 500

    protected var lastTime = System.nanoTime()

    /**
     * Triggers the behaviour. [x] and [y] parameters are not used, as this is 3D.
     * The behaviour will use the controller's pose instead.
     */
    override fun click(x : Int, y : Int) {
        val currentTime = System.nanoTime()
        if(currentTime - lastTime > timeout * 10e6) {
            lastPosition = null
            lastRotation = null
            lastTime = currentTime
        }

        val pose = hmd.getPose(TrackedDeviceType.Controller).find { it.role == handedness } ?: return
        val last = lastPosition
        val current = Vector3f(pose.position)
        val currentRotation = Quaternionf(pose.orientation)

        if(last != null) {
            val node = draggedObjectFinder.invoke() ?: return
            node.ifSpatial {
                if(trackPosition) {
                    position += current - last
                }

                if(trackRotation) {
                    rotation = currentRotation
                }
            }

            logger.debug("Node ${node.name} moved with $current - $last!")
        }

        lastPosition = current
        lastRotation = currentRotation
    }
}
