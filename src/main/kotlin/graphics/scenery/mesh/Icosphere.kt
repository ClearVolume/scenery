package graphics.scenery.mesh

import graphics.scenery.BufferUtils
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.joml.Vector3f
import java.util.*
import kotlin.math.*

/**
 * Constructs a Icosphere with the given [radius] and number of [subdivisions].
 *
 * @author Ulrik Günther <hello@ulrik.is>, based on code by Andreas Kahler, http://blog.andreaskahler.com/2009/06/creating-icosphere-mesh-in-code.html
 * @param[radius] The radius of the sphere
 * @param[subdivisions] Number of subdivisions of the base icosahedron
 */
open class Icosphere(val radius: Float, val subdivisions: Int) : Mesh("Icosphere") {
    fun MutableList<Vector3f>.addVertex(vararg v: Float) {
        this.add(Vector3f(v))
    }

    fun MutableList<Triple<Int, Int, Int>>.addFace(i: Int, j: Int, k: Int) {
        this.add(kotlin.Triple(i, j, k))
    }

    protected fun createBaseVertices(vertices: MutableList<Vector3f>, indices: MutableList<Triple<Int, Int, Int>>) {
        val s = sqrt((5.0f - sqrt(5.0f)) / 10.0f)
        val t = sqrt((5.0f + sqrt(5.0f)) / 10.0f)

        vertices.addVertex(-s, t, 0.0f)
        vertices.addVertex(s, t, 0.0f)
        vertices.addVertex(-s, -t, 0.0f)
        vertices.addVertex(s, -t, 0.0f)

        vertices.addVertex(0.0f, -s, t)
        vertices.addVertex(0.0f, s, t)
        vertices.addVertex(0.0f, -s, -t)
        vertices.addVertex(0.0f, s, -t)

        vertices.addVertex(t, 0.0f, -s)
        vertices.addVertex(t, 0.0f, s)
        vertices.addVertex(-t, 0.0f, -s)
        vertices.addVertex(-t, 0.0f, s)

        indices.addFace(0, 11, 5)
        indices.addFace(0, 5, 1)
        indices.addFace(0, 1, 7)
        indices.addFace(0, 7, 10)
        indices.addFace(0, 10, 11)

        // 5 adjacent faces
        indices.addFace(1, 5, 9)
        indices.addFace(5, 11, 4)
        indices.addFace(11, 10, 2)
        indices.addFace(10, 7, 6)
        indices.addFace(7, 1, 8)

        // 5 faces around point 3
        indices.addFace(3, 9, 4)
        indices.addFace(3, 4, 2)
        indices.addFace(3, 2, 6)
        indices.addFace(3, 6, 8)
        indices.addFace(3, 8, 9)

        // 5 adjacent faces
        indices.addFace(4, 9, 5)
        indices.addFace(2, 4, 11)
        indices.addFace(6, 2, 10)
        indices.addFace(8, 6, 7)
        indices.addFace(9, 8, 1)
    }

    protected fun refineTriangles(recursionLevel: Int,
                                  vertices: MutableList<Vector3f>,
                                  indices: MutableList<Triple<Int, Int, Int>>): MutableList<Triple<Int, Int, Int>> {
        // refine triangles
        var faces = indices
        (0 until recursionLevel).forEach {
            val faces2 = ArrayList<Triple<Int, Int, Int>>(indices.size * 3)

            for (triangle in faces) {
                // replace triangle by 4 triangles
                val a = vertices.getMiddlePoint(triangle.first, triangle.second)
                val b = vertices.getMiddlePoint(triangle.second, triangle.third)
                val c = vertices.getMiddlePoint(triangle.third, triangle.first)

                faces2.addFace(triangle.first, a, c)
                faces2.addFace(triangle.second, b, a)
                faces2.addFace(triangle.third, c, b)
                faces2.addFace(a, b, c)
            }

            faces = faces2
        }

        return faces
    }

    protected fun MutableList<Vector3f>.addVertex(v: Vector3f): Int {
        this.add(v.normalize())
        return this.size - 1
    }

    private val middlePointIndexCache = HashMap<Long, Int>()
    protected fun MutableList<Vector3f>.getMiddlePoint(p1: Int, p2: Int): Int {
        // first check if we have it already
        val firstIsSmaller = p1 < p2
        val smallerIndex = if(firstIsSmaller) { p1 } else { p2 }
        val greaterIndex = if(!firstIsSmaller) { p1 } else { p2 }
        val key: Long = (smallerIndex.toLong() shl 32) + greaterIndex.toLong()

        val ret = middlePointIndexCache[key]
        if (ret != null) {
            return ret
        }

        // not in cache, calculate it
        val point1 = this[p1]
        val point2 = this[p2]
        val middle = (point1 + point2) * 0.5f

        // add vertex makes sure point is on unit sphere
        val i = this.addVertex(middle)

        // store it, return index
        middlePointIndexCache.put(key, i)
        return i
    }

    private fun vertexToUV(n: Vector3f): Vector3f {
        val u = 0.5f - 0.5f * atan2(n.x(), -n.z())/ PI.toFloat()
        val v = 1.0f - acos(n.y()) / PI.toFloat()
        return Vector3f(u, v, 0.0f)
    }

    init {
        val vertexBuffer = ArrayList<Vector3f>()
        val indexBuffer = ArrayList<Triple<Int, Int, Int>>()

        createBaseVertices(vertexBuffer, indexBuffer)
        val faces = refineTriangles(subdivisions, vertexBuffer, indexBuffer)

        vertices = BufferUtils.allocateFloat(faces.size * 3 * 3)
        normals = BufferUtils.allocateFloat(faces.size * 3 * 3)
        texcoords = BufferUtils.allocateFloat(faces.size * 3 * 2)
        indices = BufferUtils.allocateInt(0)

        faces.forEach { f ->
            val v1 = vertexBuffer[f.first]
            val v2 = vertexBuffer[f.second]
            val v3 = vertexBuffer[f.third]
            val uv1 = vertexToUV(v1.normalize())
            val uv2 = vertexToUV(v2.normalize())
            val uv3 = vertexToUV(v3.normalize())

            (v1 * radius).get(vertices).position(vertices.position() + 3)
            (v2 * radius).get(vertices).position(vertices.position() + 3)
            (v3 * radius).get(vertices).position(vertices.position() + 3)

            v1.get(normals).position(normals.position() + 3)
            v2.get(normals).position(normals.position() + 3)
            v3.get(normals).position(normals.position() + 3)

            val uvNormal = (uv2 - uv1).cross(uv3 - uv1)
            if(uvNormal.z() < 0.0f) {
                if(uv1.x() < 0.25f) {
                    uv1.x = uv1.x() + 1.0f
                }
                if(uv2.x() < 0.25f) {
                    uv2.x = uv2.x() + 1.0f
                }
                if(uv3.x() < 0.25f) {
                    uv3.x = uv3.x() + 1.0f
                }
            }

            uv1.get(texcoords).position(texcoords.position() + 2)
            uv2.get(texcoords).position(texcoords.position() + 2)
            uv3.get(texcoords).position(texcoords.position() + 2)
        }

        vertices.flip()
        normals.flip()
        texcoords.flip()

        boundingBox = generateBoundingBox()
    }

}
