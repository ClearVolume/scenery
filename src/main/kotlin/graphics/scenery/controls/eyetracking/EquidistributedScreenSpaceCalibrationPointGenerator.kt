package graphics.scenery.controls.eyetracking

import org.joml.Vector3f
import graphics.scenery.Camera
import org.joml.Vector2f

/**
 * Generates equidistributed samples in screen-space.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class EquidistributedScreenSpaceCalibrationPointGenerator : CalibrationPointGenerator {
    /**
     * Generates a single equidistributed point in screen-space, with [index] out of [totalPointCount].
     */
    override fun generatePoint(cam : Camera, index : Int, totalPointCount : Int): CalibrationPointGenerator.CalibrationPoint {
        val points = arrayOf(
            Vector2f(0.0f, 0.5f),
            Vector2f(0.0f, 0.5f),

            Vector2f(-0.5f, 0.5f),
            Vector2f(-0.5f, -0.5f),

            Vector2f(0.5f, 0.5f),
            Vector2f(0.5f, -0.5f),

            Vector2f(-0.25f, 0.0f),
            Vector2f(0.25f, 0.0f)
        )

        val v = Vector3f(
            0.5f + 0.3f * points[index % (points.size - 1)].x(),
            0.5f + 0.3f * points[index % (points.size - 1)].y(),
            cam.nearPlaneDistance + 0.5f)

        return CalibrationPointGenerator.CalibrationPoint(v, cam.spatial().viewportToWorld(Vector2f(v.x() * 2.0f - 1.0f, v.y() * 2.0f - 1.0f)))
    }
}
