package graphics.scenery.mesh

import graphics.scenery.BufferUtils
import org.joml.Vector2f
import org.joml.Vector3f
import java.util.*
import kotlin.math.*

/**
 * Constructs a sphere with the given [radius] and number of [segments].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @param[radius] The radius of the sphere
 * @param[segments] Number of segments in latitude and longitude.
 */
open class Sphere(val radius: Float, val segments: Int) : Mesh("sphere") {
    init {
        val vbuffer = ArrayList<Float>(segments*segments*2*3)
        val nbuffer = ArrayList<Float>(segments*segments*2*3)
        val tbuffer = ArrayList<Float>(segments*segments*2*2)

        for (i in 0 until segments) {
            val theta0: Float = PI.toFloat() * i.toFloat() / segments
            val theta1: Float = PI.toFloat() * (i + 1.0f) / segments

            for (j in 0 until segments) {
                val phi0: Float = 2 * PI.toFloat() * j.toFloat() / segments
                val phi1: Float = 2 * PI.toFloat() * (j + 1.0f) / segments

                val v00 = vertexOnSphere(radius, theta0, phi0)
                val v01 = vertexOnSphere(radius, theta0, phi1)
                val v11 = vertexOnSphere(radius, theta1, phi1)
                val v10 = vertexOnSphere(radius, theta1, phi0)

                val n00 = Vector3f(v00[0], v00[1], v00[2]).normalize()
                val n01 = Vector3f(v01[0], v01[1], v01[2]).normalize()
                val n11 = Vector3f(v11[0], v11[1], v11[2]).normalize()
                val n10 = Vector3f(v10[0], v10[1], v10[2]).normalize()

                val uv00 = uvOnSphere(n00)
                val uv01 = uvOnSphere(n01)
                val uv11 = uvOnSphere(n11)
                val uv10 = uvOnSphere(n10)

                when {
                    i == 0 -> {
                        vbuffer.addAll(v10)
                        vbuffer.addAll(v11)
                        vbuffer.addAll(v00)

                        nbuffer.addAll(n10)
                        nbuffer.addAll(n11)
                        nbuffer.addAll(n00)

                        tbuffer.addAll(uv10)
                        tbuffer.addAll(uv11)
                        tbuffer.addAll(uv00)
                    }
                    i + 1 == segments -> {
                        vbuffer.addAll(v01)
                        vbuffer.addAll(v00)
                        vbuffer.addAll(v11)

                        nbuffer.addAll(n01)
                        nbuffer.addAll(n00)
                        nbuffer.addAll(n11)

                        tbuffer.addAll(uv01)
                        tbuffer.addAll(uv00)
                        tbuffer.addAll(uv11)
                    }
                    else -> {
                        vbuffer.addAll(v10)
                        vbuffer.addAll(v01)
                        vbuffer.addAll(v00)

                        nbuffer.addAll(n10)
                        nbuffer.addAll(n01)
                        nbuffer.addAll(n00)

                        tbuffer.addAll(uv10)
                        tbuffer.addAll(uv01)
                        tbuffer.addAll(uv00)

                        vbuffer.addAll(v10)
                        vbuffer.addAll(v11)
                        vbuffer.addAll(v01)

                        nbuffer.addAll(n10)
                        nbuffer.addAll(n11)
                        nbuffer.addAll(n01)

                        tbuffer.addAll(uv10)
                        tbuffer.addAll(uv11)
                        tbuffer.addAll(uv01)
                    }
                }
            }
        }

        vertices = BufferUtils.allocateFloatAndPut(vbuffer.toFloatArray())
        normals = BufferUtils.allocateFloatAndPut(nbuffer.toFloatArray())
        texcoords = BufferUtils.allocateFloatAndPut(tbuffer.toFloatArray())

        boundingBox = generateBoundingBox()
    }

    /**
     * Creates a vertex on a sphere with radius [radius], and angles [theta] and [phi].
     *
     * @param[radius] The radius of the sphere
     * @param[theta] Theta coordinate, in interval [0, PI]
     * @param[phi] Phi coordinate, in interval [0, 2PI]
     *
     * @return Vertex on a sphere, in cartesian coordinates
     */
    private fun vertexOnSphere(radius: Float, theta: Float, phi: Float) = Vector3f(
        radius * sin(theta) * cos(phi),
        radius * sin(theta) * sin(phi),
        radius * cos(theta)
    )

    /**
     * Creates UV coordinates for a given surface normal that is assumed to be
     * on a sphere.
     *
     * @param[normal] Normal vector on a sphere.
     *
     * @return UV coordinates in [0.0, 1.0] range.
     */
    private fun uvOnSphere(normal: Vector3f) = Vector2f(
        atan2(normal[2], normal[0]) / (2.0f*PI.toFloat()) + 0.5f,
        0.5f + asin(normal[1])/PI.toFloat()
    )

    private fun ArrayList<Float>.addAll(elements: Vector3f) {
        this.add(elements.x)
        this.add(elements.y)
        this.add(elements.z)
    }

    private fun ArrayList<Float>.addAll(elements: Vector2f) {
        this.add(elements.x)
        this.add(elements.y)
    }
}
