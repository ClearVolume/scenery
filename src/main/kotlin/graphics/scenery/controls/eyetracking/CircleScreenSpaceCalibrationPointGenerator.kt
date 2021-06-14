package graphics.scenery.controls.eyetracking

import graphics.scenery.Camera
import org.joml.Vector2f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screen-space calibration point generator that generates [CalibrationPointGenerator.CalibrationPoint]s
 * equidistributed on a circle.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class CircleScreenSpaceCalibrationPointGenerator : CalibrationPointGenerator {
    /**
     * Generates a single point on a circle, with [index] out of [totalPointCount].
     */
    override fun generatePoint(cam : Camera, index : Int, totalPointCount : Int): CalibrationPointGenerator.CalibrationPoint {
        val origin = 0.5f
        val radius = 0.3f

        val v = if(index == 0) {
            Vector3f(origin, origin, 0.0f)
        } else {
            Vector3f(
                origin + radius * cos(2 * PI.toFloat() * index.toFloat()/totalPointCount),
                origin + radius * sin(2 * PI.toFloat() * index.toFloat()/totalPointCount),
                0.0f)
        }
        return CalibrationPointGenerator.CalibrationPoint(v, cam.spatial().viewportToWorld(Vector2f(v.x()*2.0f-1.0f, v.y()*2.0f-1.0f)))
    }
}
