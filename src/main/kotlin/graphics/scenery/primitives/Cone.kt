package graphics.scenery.primitives

import graphics.scenery.BufferUtils
import graphics.scenery.Mesh
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.joml.Vector2f
import org.joml.Vector3f
import java.util.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Constructs a cylinder with the given [radius] and number of [segments].
 * Main [axis] can be specified and defaults to positive Y axis.
 * Adapted from https://www.freemancw.com/2012/06/opengl-cone-function/
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @param[radius] The radius of the sphere
 * @param[segments] Number of segments in latitude and longitude.
 */

class Cone(val radius: Float, val height: Float, val segments: Int, axis: Vector3f = Vector3f(0.0f, 1.0f, 0.0f)) : Mesh("cone") {
    val axis: Vector3f = Vector3f(axis).normalize()

    init {
        geometry {
            vertices = BufferUtils.allocateFloat(2 * 3 * segments * 3)
            normals = BufferUtils.allocateFloat(2 * 3 * segments * 3)
            texcoords = BufferUtils.allocateFloat(2 * 2 * segments * 3)

            val vbuffer = ArrayList<Vector3f>(segments * segments * 2 * 3)
            val nbuffer = ArrayList<Vector3f>(segments * segments * 2 * 3)
            val tbuffer = ArrayList<Vector2f>(segments * segments * 2 * 2)

            val apex = axis * height
            val center = apex - axis * height

            val e0 = perp(axis)
            val e1 = Vector3f(e0).cross(axis)

            // cone is split into [segments] sections
            val delta = 2.0f/segments * PI.toFloat()

            // draw cone by creating triangles between adjacent points on the
            // base and connecting one triangle to the apex, and one to the center
            for (i in 0 until segments) {
                val rad = delta * i
                val rad2 = delta * (i + 1)
                val v1 = center + (e0 * cos(rad) + e1 * sin(rad)) * radius
                val v2 = center + (e0 * cos(rad2) + e1 * sin(rad2)) * radius

                vbuffer.add(v1)
                vbuffer.add(apex)
                vbuffer.add(v2)

                vbuffer.add(v2)
                vbuffer.add(center)
                vbuffer.add(v1)

                val normalSide = (apex - v2).cross(v2 - v1).normalize()
                val normalBottom = axis * (-1.0f)
                nbuffer.add(normalSide)
                nbuffer.add(normalSide)
                nbuffer.add(normalSide)

                nbuffer.add(normalBottom)
                nbuffer.add(normalBottom)
                nbuffer.add(normalBottom)

                tbuffer.add(Vector2f(cos(rad) * 0.5f + 0.5f, sin(rad) * 0.5f + 0.5f))
                tbuffer.add(Vector2f(0.5f, 0.5f))
                tbuffer.add(Vector2f(cos(rad2) * 0.5f + 0.5f, sin(rad2) * 0.5f + 0.5f))

                tbuffer.add(Vector2f(cos(rad2) * 0.5f + 0.5f, sin(rad2) * 0.5f + 0.5f))
                tbuffer.add(Vector2f(0.5f, 0.5f))
                tbuffer.add(Vector2f(cos(rad) * 0.5f + 0.5f, sin(rad) * 0.5f + 0.5f))
            }

            vbuffer.forEach { v -> v.get(vertices).position(vertices.position() + 3) }
            nbuffer.forEach { n -> n.get(normals).position(normals.position() + 3) }
            tbuffer.forEach { uv -> uv.get(texcoords).position(texcoords.position() + 2) }

            vertices.flip()
            normals.flip()
            texcoords.flip()
        }

        boundingBox = generateBoundingBox()
    }

    fun perp(v: Vector3f): Vector3f {
        var min = v.x()
        var cardinalAxis = Vector3f(1.0f, 0.0f, 0.0f)

        if(abs(v.y()) < min) {
            min = abs(v.y())
            cardinalAxis = Vector3f(0.0f, 1.0f, 0.0f)
        }

        if(abs(v.z()) < min) {
            cardinalAxis = Vector3f(0.0f, 0.0f, 1.0f)
        }

        return cardinalAxis
    }

}
