package graphics.scenery.controls.behaviours

import graphics.scenery.Camera
import graphics.scenery.Scene
import graphics.scenery.Mesh
import graphics.scenery.backends.Renderer
import graphics.scenery.utils.LazyLogger
import org.joml.Vector2f
import org.joml.Vector3f
import org.scijava.ui.behaviour.ClickBehaviour
import kotlin.reflect.KProperty

/**
 * Creates a Mesh with a click at the mouse position.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 */
open class MeshAdder constructor(protected val name: String,
                                 protected val renderer: Renderer,
                                 protected val scene: Scene,
                                 protected val camera: () -> Camera?,
                                 meshLambda: () -> Mesh
) : ClickBehaviour {
    protected val logger by LazyLogger()

    protected val cam: Camera? by CameraDelegate()

    protected val mesh = meshLambda.invoke()

    /** Camera delegate class, converting lambdas to Cameras. */
    protected inner class CameraDelegate {
        /** Returns the [graphics.scenery.Camera] resulting from the evaluation of [camera] */
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Camera? {
            return camera.invoke()
        }

        /** Setting the value is not supported */
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Camera?) {
            throw UnsupportedOperationException()
        }
    }


    /**
     * This is the action executed upon triggering this action, with [x] and [y] being
     * the screen-space coordinates.
     */
    override fun click(x: Int, y: Int) {
        val width = cam!!.width
        val height = cam!!.height
        val posX = (x - width / 2.0f) / (width / 2.0f)
        val posY = -1.0f * (y - height / 2.0f) / (height / 2.0f)
        mesh.parent = scene
        val mousePosition = cam!!.spatial().viewportToView(Vector2f(posX, posY))
        val position4D = cam!!.spatial().viewToWorld(mousePosition)
        val position = Vector3f(position4D.x(), position4D.y(), position4D.z())
        mesh.spatial().position = position
        scene.addChild(mesh)
    }
}
