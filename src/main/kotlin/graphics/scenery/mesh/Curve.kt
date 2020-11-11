package graphics.scenery.mesh

import graphics.scenery.BufferUtils
import graphics.scenery.HasGeometry
import graphics.scenery.Spline
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.toFloatArray
import graphics.scenery.utils.extensions.xyz
import org.joml.*
import kotlin.math.acos

/**
 * Constructs a geometry along the calculates points of a Spline (in this case a Catmull Rom Spline).
 * This class inherits from Node and HasGeometry
 * The number n corresponds to the number of segments you wish to have between you control points.
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class Curve(curve: Spline, baseShape: () -> ArrayList<Vector3f>): Mesh("CurveGeometry"), HasGeometry {
    private val chain = curve.splinePoints()

    /**
     * This function renders the spline.
     * [baseShape] It takes a lambda as a parameter, which is the shape of the
     * curve.
     * If you choose, for example, to have a square as a base shape, your spline will look like
     * a banister. Please not that the base shape needs an equal number of points in each segments but it
     * can very well vary in thickness.
     */
    init {
        val bases = computeFrenetFrames(chain as ArrayList<Vector3f>).map { (t, n, b, tr) ->
            if(n != null && b != null) {
                val inverseMatrix = Matrix4f(n.x(), b.x(), t.x(), 0f,
                                             n.y(), b.y(), t.y(), 0f,
                                             n.z(), b.z(), t.z(), 0f,
                                            0f, 0f ,0f ,1f).invert()
                val nn = Vector3f(inverseMatrix[0, 0], inverseMatrix[1, 0], inverseMatrix[2, 0]).normalize()
                val nb = Vector3f(inverseMatrix[0, 1],inverseMatrix[1, 1], inverseMatrix[1, 2]).normalize()
                val nt = Vector3f(inverseMatrix[0, 2], inverseMatrix[2, 1], inverseMatrix[2, 2]).normalize()
                Matrix4f(
                    nn.x(), nb.x(), nt.x(), 0f,
                    nn.y(), nb.y(), nt.y(), 0f,
                    nn.z(), nb.z(), nt.z(), 0f,
                    tr.x(), tr.y(), tr.z(), 1f)
            }
            else {
                throw IllegalStateException("Tangent and normal must not be null!")
            }
        }
        val curveGeometry = bases.map { basis: Matrix4f ->
            baseShape.invoke().map { v ->
                basis.transformPosition(v)
            }
        }

        val verticesVectors = calculateTriangles(curveGeometry)

        vertices = BufferUtils.allocateFloat(verticesVectors.size * 3)
        verticesVectors.forEach{
            vertices.put(it.toFloatArray())
        }
        vertices.flip()
        texcoords = BufferUtils.allocateFloat(verticesVectors.size * 2)
        recalculateNormals()
    }

    /**
     * This function calculates the tangent at a given index in the catmull rom curve.
     * [i] index of the curve (not the geometry!)
     */
    private fun getTangent(i: Int): Vector3f {
        val s = chain.size
        return when(i) {
            0 -> ((chain[i+1] - chain[i]).normalize())
            (s-2) -> ((chain[i+1] - chain[i]).normalize())
            (s-1) -> ((chain[i] - chain[i-1]).normalize())
            else -> ((chain[i+1] - chain[i-1]).normalize())
        }
    }

    /**
     * Data class to store Frenet frames (wandering coordinate systems), consisting of [tangent], [normal], [bitangent]
     */
    data class FrenetFrame(val tangent: Vector3f, var normal: Vector3f?, var bitangent: Vector3f?, val translation: Vector3f)
    /**
     * This function returns the frenet frames along the curve. This is essentially a new
     * coordinate system which represents the form of the curve. For details concerning the
     * calculation see: http://www.cs.indiana.edu/pub/techreports/TR425.pdf
     */
    fun computeFrenetFrames(curve: ArrayList<Vector3f>): List<FrenetFrame> {

        val frenetFrameList = ArrayList<FrenetFrame>(curve.size)

        if(curve.isEmpty()) {
            return frenetFrameList
        }

        //adds all the tangent vectors
        curve.forEachIndexed { index, _ ->
            val frenetFrame = FrenetFrame(getTangent(index), null, null, curve[index])
            frenetFrameList.add(frenetFrame)
        }

        //initial normal vector perpendicular to first tangent vector
        val vec = if(frenetFrameList[0].tangent.x() >= 0.9f || frenetFrameList[0].tangent.z() >= 0.9f) {
            Vector3f(0f, 1f, 0f)
        }
        else {
            Vector3f(1f, 0f, 0f)
        }

        val normal = Vector3f(frenetFrameList[0].tangent).cross(vec).normalize()

        frenetFrameList[0].normal = normal
        frenetFrameList[0].bitangent = Vector3f(frenetFrameList[0].tangent).cross(normal).normalize()

        frenetFrameList.windowed(2,1).forEach { (firstFrame, secondFrame) ->
            val b = Vector3f(firstFrame.tangent).cross(secondFrame.tangent)
            //if there is no substantial difference between two tangent vectors, the frenet frame need not to change
            if (b.length() < 0.0001f) {
                secondFrame.normal = firstFrame.normal
                secondFrame.bitangent = firstFrame.bitangent
            } else {
                val firstNormal = firstFrame.normal

                val theta = acos(firstFrame.tangent.dot(secondFrame.tangent))
                if (normal != null && firstNormal != null) {
                    val rotationMatrix = Matrix4f().rotate(AxisAngle4f(theta, firstNormal))
                    val normal4D = Vector4f(firstNormal.x(), firstNormal.y(), firstNormal.z(), 1f)
                    secondFrame.normal = rotationMatrix.transform(normal4D).xyz().normalize()
                }
                else {
                    throw IllegalStateException("Normals must not be null!")
                }
                val secondFrameTangent = Vector3f(secondFrame.tangent)
                secondFrame.bitangent = secondFrameTangent.cross(secondFrame.normal).normalize()
            }
        }
        return frenetFrameList.filterNot { it.bitangent!!.toFloatArray().all { value -> value.isNaN() } &&
                                            it.normal!!.toFloatArray().all{ value -> value.isNaN()}}
    }

    /**
     * This function calculates the triangles for the the rendering. It takes as a parameter
     * the [curveGeometry] List which contains all the baseShapes transformed and translated
     * along the curve.
     */
    private fun calculateTriangles(curveGeometry: List<List<Vector3f>>): ArrayList<Vector3f> {
        val verticesVectors = ArrayList<Vector3f>()
        if(curveGeometry.isEmpty()) {
            return verticesVectors
        }
        //if none of the lists in the curveGeometry differ in size, distinctBy leaves only one element
        if(curveGeometry.distinctBy{ it.size }.size == 1) {
            curveGeometry.dropLast(1).forEachIndexed { shapeIndex, shape ->
                shape.dropLast(1).forEachIndexed { vertexIndex, _ ->

                    verticesVectors.add(curveGeometry[shapeIndex][vertexIndex])
                    verticesVectors.add(curveGeometry[shapeIndex][vertexIndex + 1])
                    verticesVectors.add(curveGeometry[shapeIndex + 1][vertexIndex])

                    verticesVectors.add(curveGeometry[shapeIndex][vertexIndex + 1])
                    verticesVectors.add(curveGeometry[shapeIndex + 1][vertexIndex + 1])
                    verticesVectors.add(curveGeometry[shapeIndex + 1][vertexIndex])
                }
                verticesVectors.add(curveGeometry[shapeIndex][0])
                verticesVectors.add(curveGeometry[shapeIndex + 1][0])
                verticesVectors.add(curveGeometry[shapeIndex + 1][shape.lastIndex])

                verticesVectors.add(curveGeometry[shapeIndex + 1][shape.lastIndex])
                verticesVectors.add(curveGeometry[shapeIndex][shape.lastIndex])
                verticesVectors.add(curveGeometry[shapeIndex][0])
            }
        }
        else {
            throw IllegalArgumentException("The baseShapes must not differ in size!")
        }
        return verticesVectors
    }

    /**
     * Getter for the curve.
     */
    fun getCurve(): ArrayList<Vector3f> {
        return chain as ArrayList<Vector3f>
    }
}
