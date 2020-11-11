package graphics.scenery.mesh

import graphics.scenery.BufferUtils
import graphics.scenery.Material
import graphics.scenery.Node
import graphics.scenery.OrientedBoundingBox
import graphics.scenery.utils.extensions.*
import org.joml.Vector3f
import kotlin.jvm.JvmOverloads

/**
 * Constructs a Box [Node] with the dimensions given in [sizes]
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[sizes] The x/y/z sizes of the box
 */
open class Box @JvmOverloads constructor(val sizes: Vector3f = Vector3f(1.0f, 1.0f, 1.0f), val insideNormals: Boolean = false) : Mesh("box") {
    init {
        val side = 1.0f
        val side2 = side / 2.0f

        boundingBox = OrientedBoundingBox(this,
            -side2 * sizes.x(),
            -side2 * sizes.y(),
            -side2 * sizes.z(),
            side2 * sizes.x(),
            side2 * sizes.y(),
            side2 * sizes.z())

        vertices = BufferUtils.allocateFloatAndPut(floatArrayOf(
            // Front
            -sizes.x() * side2, -side2*sizes.y(), side2*sizes.z(),
            sizes.x() * side2, -side2*sizes.y(), side2*sizes.z(),
            sizes.x() * side2, side2*sizes.y(), side2*sizes.z(),
            -sizes.x() * side2, side2*sizes.y(), side2*sizes.z(),

            // Right
            sizes.x() * side2, -side2*sizes.y(), side2*sizes.z(),
            sizes.x() * side2, -side2*sizes.y(), -side2*sizes.z(),
            sizes.x() * side2, side2*sizes.y(), -side2*sizes.z(),
            sizes.x() * side2, side2*sizes.y(), side2*sizes.z(),

            // Back
            -sizes.x() * side2, -side2*sizes.y(), -side2*sizes.z(),
            -sizes.x() * side2, side2*sizes.y(), -side2*sizes.z(),
            sizes.x() * side2, side2*sizes.y(), -side2*sizes.z(),
            sizes.x() * side2, -side2*sizes.y(), -side2*sizes.z(),

            // Left
            -sizes.x() * side2, -side2*sizes.y(), side2*sizes.z(),
            -sizes.x() * side2, side2*sizes.y(), side2*sizes.z(),
            -sizes.x() * side2, side2*sizes.y(), -side2*sizes.z(),
            -sizes.x() * side2, -side2*sizes.y(), -side2*sizes.z(),

            // Bottom
            -sizes.x() * side2, -side2*sizes.y(), side2*sizes.z(),
            -sizes.x() * side2, -side2*sizes.y(), -side2*sizes.z(),
            sizes.x() * side2, -side2*sizes.y(), -side2*sizes.z(),
            sizes.x() * side2, -side2*sizes.y(), side2*sizes.z(),
            // Top
            -sizes.x() * side2, side2*sizes.y(), side2*sizes.z(),
            sizes.x() * side2, side2*sizes.y(), side2*sizes.z(),
            sizes.x() * side2, side2*sizes.y(), -side2*sizes.z(),
            -sizes.x() * side2, side2*sizes.y(), -side2*sizes.z()
        ))

        val flip: Float = if(insideNormals) { -1.0f } else { 1.0f }
        normals = BufferUtils.allocateFloatAndPut(floatArrayOf(
            // Front
            0.0f, 0.0f, 1.0f*flip,
            0.0f, 0.0f, 1.0f*flip,
            0.0f, 0.0f, 1.0f*flip,
            0.0f, 0.0f, 1.0f*flip,
            // Right
            1.0f*flip, 0.0f, 0.0f,
            1.0f*flip, 0.0f, 0.0f,
            1.0f*flip, 0.0f, 0.0f,
            1.0f*flip, 0.0f, 0.0f,
            // Back
            0.0f, 0.0f, -1.0f*flip,
            0.0f, 0.0f, -1.0f*flip,
            0.0f, 0.0f, -1.0f*flip,
            0.0f, 0.0f, -1.0f*flip,
            // Left
            -1.0f*flip, 0.0f, 0.0f,
            -1.0f*flip, 0.0f, 0.0f,
            -1.0f*flip, 0.0f, 0.0f,
            -1.0f*flip, 0.0f, 0.0f,
            // Bottom
            0.0f, -1.0f*flip, 0.0f,
            0.0f, -1.0f*flip, 0.0f,
            0.0f, -1.0f*flip, 0.0f,
            0.0f, -1.0f*flip, 0.0f,
            // Top
            0.0f, 1.0f*flip, 0.0f,
            0.0f, 1.0f*flip, 0.0f,
            0.0f, 1.0f*flip, 0.0f,
            0.0f, 1.0f*flip, 0.0f
        ))

        indices = BufferUtils.allocateIntAndPut(intArrayOf(
            0, 1, 2, 0, 2, 3,
            4, 5, 6, 4, 6, 7,
            8, 9, 10, 8, 10, 11,
            12, 13, 14, 12, 14, 15,
            16, 17, 18, 16, 18, 19,
            20, 21, 22, 20, 22, 23
        ))

        texcoords = BufferUtils.allocateFloatAndPut(floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
        ))

        boundingBox = generateBoundingBox()
    }

    companion object {
        /**
         * Creates a box with a hull of size [outerSize] and a wall thickness given in [wallThickness].
         * Returns a container node containing both.
         */
        @JvmStatic fun hulledBox(outerSize: Vector3f = Vector3f(1.0f, 1.0f, 1.0f), wallThickness: Float = 0.05f): Node {
            val container = Mesh()
            val outer = Box(outerSize, insideNormals = false)
            container.addChild(outer)

            val innerSize = outerSize - Vector3f(1.0f, 1.0f, 1.0f) * wallThickness * 0.5f
            val inner = Box(innerSize, insideNormals = true)
            inner.material.cullingMode = Material.CullingMode.Front
            container.addChild(inner)

            return container
        }
    }
}
