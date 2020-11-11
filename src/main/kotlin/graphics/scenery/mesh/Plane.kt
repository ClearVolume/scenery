package graphics.scenery.mesh

import graphics.scenery.BufferUtils
import org.joml.Vector3f

/**
 * Constructs a plane with the dimensions given in [sizes].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @param[sizes] The dimensions of the plane.
 */
open class Plane(sizes: Vector3f) : Mesh() {
    init {
        this.scale = sizes
        this.name = "plane"
        val side = 2.0f
        val side2 = side / 2.0f

        vertices = BufferUtils.allocateFloatAndPut(floatArrayOf(
                // Front
                -side2, -side2, side2,
                side2, -side2, side2,
                side2,  side2, side2,
                -side2,  side2, side2
        ))

        normals = BufferUtils.allocateFloatAndPut(floatArrayOf(
                // Front
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        ))

        indices = BufferUtils.allocateIntAndPut(intArrayOf(
                0,1,2,0,2,3
        ))

        texcoords = BufferUtils.allocateFloatAndPut(floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        ))

        boundingBox = generateBoundingBox()
    }
}
