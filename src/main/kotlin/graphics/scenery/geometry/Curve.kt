package graphics.scenery.geometry

import graphics.scenery.BufferUtils
import graphics.scenery.Mesh
import graphics.scenery.utils.extensions.toFloatArray
import org.joml.*
import kotlin.Float.Companion.MIN_VALUE
import kotlin.math.acos

/**
 * Constructs a geometry along the calculates points of a Spline (in this case a Catmull Rom Spline).
 * This class inherits from Node and HasGeometry
 * The number n corresponds to the number of segments you wish to have between your control points.
 * The spline and the baseShape lambda must both have the same number of elements, otherwise, the curve is no
 * longer well defined. Concerning the individual baseShapes, no lines must cross for the body of the curve to
 * be visualized flawlessly. Furthermore, all baseShapes ought to be convex.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 * @param [spline] the spline along which the geometry will be rendered
 * @param [baseShape] a lambda which returns all the baseShapes along the curve
 * @param [firstPerpendicularVector] vector to which the first frenet tangent shall be perpendicular to.
 */
class Curve(spline: Spline, private val firstPerpendicularVector: Vector3f = Vector3f(0f, 0f, 0f),
            baseShape: () -> List<List<Vector3f>>): Mesh("CurveGeometry") {
    private val chain = spline.splinePoints()
    private val sectionVertices = spline.verticesCountPerSection()
    private val countList = ArrayList<Int>(50).toMutableList()

    /*
     * This function renders the spline.
     * [baseShape] It takes a lambda as a parameter, which is the shape of the
     * curve.
     * If you choose, for example, to have a square as a base shape, your spline will look like
     * a banister.
     */
    init {
        if (chain.isEmpty()) {
            logger.warn("The spline provided for the Curve is empty.")
        }
        val bases = computeFrenetFrames(chain as ArrayList<Vector3f>).map { (t, n, b, tr) ->
            val inverseMatrix = Matrix3f(b.x(), n.x(), t.x(),
                    b.y(), n.y(), t.y(),
                    b.z(), n.z(), t.z()).invert()
            val nb = Vector3f()
            inverseMatrix.getColumn(0, nb).normalize()
            val nn = Vector3f()
            inverseMatrix.getColumn(1, nn).normalize()
            val nt = Vector3f()
            inverseMatrix.getColumn(2, nt).normalize()
            Matrix4f(
                    nb.x(), nn.x(), nt.x(), 0f,
                    nb.y(), nn.y(), nt.y(), 0f,
                    nb.z(), nn.z(), nt.z(), 0f,
                    tr.x(), tr.y(), tr.z(), 1f)
        }
        val baseShapes = baseShape.invoke()

        var partialCurveSize = 1
        baseShapes.windowed(2, 1) { frame ->
            when (frame[0].size) {
                frame[1].size -> {
                    partialCurveSize++
                }
                else -> {
                    countList.add(partialCurveSize)
                    partialCurveSize = 1
                }
            }
        }
        countList.add(partialCurveSize)
        var position = 0
        var lastShapeUnique = false
        if(countList.last() == 1) {
            countList.removeAt(countList.lastIndex)
            lastShapeUnique = true
        }

        countList.forEach {count ->
            val partialCurve = Mesh("partialCurve")
            val partialCurveGeometry = ArrayList<ArrayList<Vector3f>>(count)
            for(j in 0 until count) {
                val shape = baseShapes[position]
                val shapeVertexList = ArrayList<Vector3f>(shape.size)
                shape.forEach {
                    val vec = Vector3f()
                    shapeVertexList.add(bases[position].transformPosition(it, vec))
                }
                partialCurveGeometry.add(shapeVertexList)
                position++
            }
            val helpPosition = position
            //fill the gaps between the different shapes
            if(helpPosition < bases.lastIndex) {
                val shape = baseShapes[helpPosition-1]
                val shapeVertexList = ArrayList<Vector3f>(shape.size)
                shape.forEach {
                    val vec = Vector3f()
                    shapeVertexList.add(bases[helpPosition].transformPosition(it, vec))
                }
                partialCurveGeometry.add(shapeVertexList)
            }
            //edge case: the last shape is different from its predecessor
            if(lastShapeUnique && helpPosition == bases.lastIndex) {
                val shape = baseShapes[helpPosition-1]
                val shapeVertexList = ArrayList<Vector3f>(shape.size)
                shape.forEach {
                    val vec = Vector3f()
                    shapeVertexList.add(bases[helpPosition].transformPosition(it, vec))
                }
                partialCurveGeometry.add(shapeVertexList)
            }
            val remainder = partialCurveGeometry.size%sectionVertices
            val n = ((partialCurveGeometry.size)/sectionVertices).toInt()
            val add = if(n == 0) { 1 } else { remainder/n }
            partialCurveGeometry.windowed(sectionVertices + add, sectionVertices-1 + add, true)
            { section ->
                val i = when {
                    section.contains(partialCurveGeometry.first()) && section.contains(partialCurveGeometry.last()) -> {
                        0
                    }
                    section.contains(partialCurveGeometry.first()) -> {
                        1
                    }
                    section.contains(partialCurveGeometry.last()) -> {
                        2
                    }
                    else -> {
                        3
                    }
                }
                val partialCurveSectionVertices = calculateTriangles(section, i)
                val partialCurveSection = PartialCurve(partialCurveSectionVertices)
                partialCurve.addChild(partialCurveSection)
            }
            this.addChild(partialCurve)
        }
    }

    /**
     * This function calculates the tangent at a given index.
     * [i] index of the curve (not the geometry!)
     */
    private fun getTangent(i: Int): Vector3f {
        if(chain.size >= 3) {
            val tangent = Vector3f()
            when (i) {
                0 -> { ((chain[1].sub(chain[0], tangent)).normalize()) }
                1 -> { ((chain[2].sub(chain[0], tangent)).normalize()) }
                chain.lastIndex - 1 -> { ((chain[i + 1].sub(chain[i - 1], tangent)).normalize()) }
                chain.lastIndex -> { ((chain[i].sub(chain[i - 1], tangent)).normalize()) }
                else -> {
                    chain[i+1].sub(chain[i-1], tangent).normalize()
                }
            }
            return tangent
        }
        else {
            throw Exception("The spline deosn't provide enough points")
        }
    }

    /**
     * Data class to store Frenet frames (wandering coordinate systems), consisting of [tangent], [normal], [binormal]
     */
    data class FrenetFrame(val tangent: Vector3f, var normal: Vector3f, var binormal: Vector3f, val translation: Vector3f)
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
            val frenetFrame = FrenetFrame(getTangent(index), Vector3f(), Vector3f(), curve[index])
            frenetFrameList.add(frenetFrame)
        }
        var min = MIN_VALUE
        val vec = Vector3f(0f, 0f, 0f)
        vec.set(firstPerpendicularVector)
        if(vec == Vector3f(0f, 0f, 0f)) {
            val normal = Vector3f()
            if (frenetFrameList[0].tangent.x() <= min) {
                min = frenetFrameList[0].tangent.x()
                normal.set(1f, 0f, 0f)
            }
            if (frenetFrameList[0].tangent.y() <= min) {
                min = frenetFrameList[0].tangent.y()
                normal.set(0f, 1f, 0f)
            }
            if (frenetFrameList[0].tangent.z() <= min) {
                normal.set(0f, 0f, 1f)
            } else {
                normal.set(1f, 0f, 0f).normalize()
            }
            frenetFrameList[0].tangent.cross(normal, vec).normalize()
        }
        else { vec.normalize() }
        frenetFrameList[0].tangent.cross(vec, frenetFrameList[0].normal).normalize()
        frenetFrameList[0].tangent.cross(frenetFrameList[0].normal, frenetFrameList[0].binormal).normalize()

        frenetFrameList.windowed(2,1).forEach { (firstFrame, secondFrame) ->
            val b = Vector3f(firstFrame.tangent).cross(secondFrame.tangent)
            secondFrame.normal = firstFrame.normal.normalize()
            //if there is no substantial difference between two tangent vectors, the frenet frame need not to change
            if (b.length() > 0.00001f) {
                val firstNormal = firstFrame.normal
                b.normalize()
                val theta = acos(firstFrame.tangent.dot(secondFrame.tangent).coerceIn(-1f, 1f))
                val q = Quaternionf(AxisAngle4f(theta, b)).normalize()
                secondFrame.normal = q.transform(Vector3f(firstNormal)).normalize()
            }
            secondFrame.tangent.cross(secondFrame.normal, secondFrame.binormal).normalize()
        }
        return frenetFrameList.filterNot { it.binormal.toFloatArray().all { value -> value.isNaN() } &&
                it.normal.toFloatArray().all{ value -> value.isNaN()}}
    }

    companion object VerticesCalculation {
        /**
         * This function calculates the triangles for the the rendering. It takes as a parameter
         * the [curveGeometry] List which contains all the baseShapes transformed and translated
         * along the curve.
         */
        fun calculateTriangles(curveGeometry: List<List<Vector3f>>, addCoverOrTop: Int = 2): ArrayList<Vector3f> {
            val verticesVectors = ArrayList<Vector3f>(curveGeometry.flatten().size * 6 + curveGeometry[0].size + 1)
            if (curveGeometry.isEmpty()) {
                return verticesVectors
            }
            if (addCoverOrTop == 0 || addCoverOrTop == 1) {
                verticesVectors.addAll(getCoverVertices(curveGeometry[0], true))
            }
            //if none of the lists in the curveGeometry differ in size, distinctBy leaves only one element
            if (curveGeometry.distinctBy { it.size }.size == 1) {
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
            } else {
                throw IllegalArgumentException("The baseShapes must not differ in size!")
            }
            if (addCoverOrTop == 0 || addCoverOrTop == 2) {
                verticesVectors.addAll(getCoverVertices(curveGeometry.last(), false))
            }
            return verticesVectors
        }

        /**
        This function contains a generalized algorithm for the cover of a curve. It works like a pot and a lit. If you cover
        the bottom of a curve, the triangles should be arranged counterclockwise, for the top clockwise - this is signified
        by [ccw].
         */
        private fun getCoverVertices(list: List<Vector3f>, ccw: Boolean): ArrayList<Vector3f> {
            val size = list.size
            val verticesList = ArrayList<Vector3f>(size + (size / 2))
            val workList = ArrayList<Vector3f>(size)
            workList.addAll(list)
            if (size >= 3) {
                /* The algorithm must not stop before the last triangle. The next five lines ensure, therefore,
                   that the last triangle, which contains the last point as well as the first point, is included.
             */
                when (size % 1) {
                    0 -> {  workList.add(list[0])
                        workList.add(list[1]) }
                    1 -> { workList.add(list[0]) }
                }
                val newList = ArrayList<Vector3f>((size + (size / 2)) / 2)
                workList.windowed(3, 2) { triangle ->
                    if (ccw) {
                        verticesList.add(triangle[0])
                        verticesList.add(triangle[2])
                        verticesList.add(triangle[1])
                    } else {
                        for (i in 0..2) {
                            verticesList.add(triangle[i])
                        }
                    }
                    newList.add(triangle[0])
                }
                verticesList.addAll(getCoverVertices(newList, ccw))
            }
            return verticesList
        }
    }

    /**
     * Each children of the curve must be, per definition, another Mesh. Therefore this class turns a List of
     * vertices into a Mesh.
     */
    class PartialCurve(verticesVectors: ArrayList<Vector3f>) : Mesh("PartialCurve") {
        init {
            geometry {
                vertices = BufferUtils.allocateFloat(verticesVectors.size * 3)
                verticesVectors.forEach {
                    vertices.put(it.toFloatArray())
                }
                vertices.flip()
                texcoords = BufferUtils.allocateFloat(verticesVectors.size * 2)
                recalculateNormals()
            }
        }
    }

    /**
     * Getter for the curve.
     */
    fun getCurve(): ArrayList<Vector3f> {
        return chain as ArrayList<Vector3f>
    }

}

