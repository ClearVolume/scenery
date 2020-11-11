package graphics.scenery.mesh

import graphics.scenery.BufferUtils
import graphics.scenery.GeometryType
import org.joml.Vector3f
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constructs a cylinder with the given [radius] and number of [segments].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @param[radius] The radius of the sphere
 * @param[segments] Number of segments in latitude and longitude.
 */

class Cylinder(var radius: Float, var height: Float, var segments: Int) : Mesh("cylinder") {
    init {
        geometryType = GeometryType.TRIANGLE_STRIP

        val vbuffer = ArrayList<Float>(segments * segments * 2 * 3)
        val nbuffer = ArrayList<Float>(segments * segments * 2 * 3)
        val tbuffer = ArrayList<Float>(segments * segments * 2 * 2)

        val delta = 2.0f * PI.toFloat() / segments.toFloat()
        val c = cos(delta * 1.0).toFloat()
        val s = sin(delta * 1.0).toFloat()

        var x2 = radius
        var z2 = 0.0f

        for (i: Int in 0..segments) {
            val texcoord = i / segments.toFloat()
            val normal = 1.0f / sqrt(x2 * x2 * 1.0 + z2 * z2 * 1.0).toFloat()
            val xn = x2 * normal
            val zn = z2 * normal

            nbuffer.add(xn)
            nbuffer.add(0.0f)
            nbuffer.add(zn)

            tbuffer.add(texcoord)
            tbuffer.add(0.0f)

            vbuffer.add(0.0f + x2)
            vbuffer.add(0.0f)
            vbuffer.add(0.0f + z2)

            nbuffer.add(xn)
            nbuffer.add(0.0f)
            nbuffer.add(zn)

            tbuffer.add(texcoord)
            tbuffer.add(1.0f)

            vbuffer.add(0.0f + x2)
            vbuffer.add(0.0f + height)
            vbuffer.add(0.0f + z2)

            val x3 = x2
            x2 = c * x2 - s * z2
            z2 = s * x3 + c * z2
        }

        vertices = BufferUtils.allocateFloatAndPut(vbuffer.toFloatArray())
        normals = BufferUtils.allocateFloatAndPut(nbuffer.toFloatArray())
        texcoords = BufferUtils.allocateFloatAndPut(tbuffer.toFloatArray())

        boundingBox = generateBoundingBox()
    }

    companion object {
        @JvmStatic fun betweenPoints(p1: Vector3f, p2: Vector3f, radius: Float = 0.02f, height: Float = 1.0f, segments: Int = 16): Cylinder {
            val cylinder = Cylinder(radius, height, segments)
            cylinder.orientBetweenPoints(p1, p2, rescale = true, reposition = true)
            return cylinder
        }
    }

}
