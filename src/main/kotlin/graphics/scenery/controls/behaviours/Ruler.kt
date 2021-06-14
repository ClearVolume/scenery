package graphics.scenery.controls.behaviours

import graphics.scenery.Camera
import graphics.scenery.Scene
import graphics.scenery.primitives.Line
import graphics.scenery.primitives.TextBoard
import graphics.scenery.utils.LazyLogger
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import org.scijava.ui.behaviour.DragBehaviour

/**
 * Draws a line on a mouse drag - just like when you draw a line in paint.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 */
class Ruler(private val name: String, private val camera: () -> Camera?, private val scene: Scene): DragBehaviour {

    //line which is to be drawn
    private val line = Line(simple = true)
    //position on the mouse click; start of the line
    private val origin = Vector3f()
    private val finalLength = Vector3f()
    private val logger by LazyLogger()
    private val cam = camera.invoke()

    /** Setup the line and delete the old one */
    override fun init(p0: Int, p1: Int) {
        origin.set(getMousePositionIn3D(p0, p1))
        line.addPoint(origin)
        line.parent = scene
        scene.addChild(line)
        scene.removeChild("DistanceTextBoard")
    }

    /** Drag the line*/
    override fun drag(p0: Int, p1: Int) {
        val position = getMousePositionIn3D(p0, p1)
        line.clearPoints()
        line.addPoint(origin)
        line.addPoint(position)
    }

    /**Finish the line*/
    override fun end(p0: Int, p1: Int) {
        val endPosition = getMousePositionIn3D(p0, p1)
        line.clearPoints()
        line.addPoint(origin)
        line.addPoint(endPosition)
        endPosition.sub(origin, finalLength)
        logger.info("The line is ${finalLength.length()}")
        val board = TextBoard()
        board.text = "Distance: ${finalLength.length()} units"
        board.name = "DistanceTextBoard"
        board.transparent = 0
        board.fontColor = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)
        board.backgroundColor = Vector4f(100f, 100f, 100f, 1.0f)
        val boardPosition = Vector3f()
        origin.add(endPosition, boardPosition).mul(0.5f)
        board.spatial {
            position = boardPosition.mul(0.5f)
            scale = Vector3f(0.5f, 0.5f, 0.5f)
        }
        scene.addChild(board)
    }

    /** Get the position of your mouse in 3D world coordinates*/
    private fun getMousePositionIn3D(p0: Int, p1: Int): Vector3f {
        val width = cam!!.width
        val height = cam.height
        val posX = (p0 - width / 2.0f) / (width / 2.0f)
        val posY = -1.0f * (p1 - height / 2.0f) / (height / 2.0f)
        val mousePosition = cam.spatial().viewportToView(Vector2f(posX, posY))
        val position4D = cam.spatial().viewToWorld(mousePosition)
        return Vector3f(position4D.x(), position4D.y(), position4D.z())
    }
}
