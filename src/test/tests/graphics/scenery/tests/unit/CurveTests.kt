package graphics.scenery.tests.unit

import graphics.scenery.numerics.Random
import graphics.scenery.CatmullRomSpline
import graphics.scenery.mesh.Curve
import graphics.scenery.UniformBSpline
import graphics.scenery.utils.LazyLogger
import org.joml.Vector3f
import org.junit.Test
import org.lwjgl.BufferUtils
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

/**
 * This is the test class for the [Curve]
 *
 * @author Justin Bürger, burger@mpi-cbg.com
 */
class CurveTests {
    private val logger by LazyLogger()

    /**
     * Tests if the vectors are normalized and if the bitangent and normal vector are becoming null.
     */
    @Test
    fun testCurve() {
        logger.info("This is the test for the Curve.")
        val point1 = Random.random3DVectorFromRange(-30f, -10f)
        val point2 = Random.random3DVectorFromRange(-9f, 20f)
        val point3 = Random.random3DVectorFromRange(21f, 30f)
        val point4 = Random.random3DVectorFromRange(31f, 100f)

        val controlPoints = arrayListOf(point1, point2, point3, point4)

        val curve = CatmullRomSpline(controlPoints)

        fun triangle(): ArrayList<Vector3f> {
            val list = ArrayList<Vector3f>()
            list.add(Vector3f(0.3f, 0.3f, 0f))
            list.add(Vector3f(0.3f, -0.3f, 0f))
            list.add(Vector3f(-0.3f, -0.3f, 0f))
            return list
        }

        val geometry = Curve(curve) { triangle() }
        val frenetFrames = geometry.computeFrenetFrames(geometry.getCurve())
        assertEquals(curve.splinePoints(), geometry.getCurve())
        assertNotNull(frenetFrames.forEach { it.normal })
        assertNotNull(frenetFrames.forEach{ it.bitangent })
        assertEquals(frenetFrames.filter { it.bitangent?.length()!! < 1.001f && it.bitangent?.length()!! > 0.999f },
                frenetFrames)
        assertEquals(frenetFrames.filter { it.normal?.length()!! < 1.001f && it.normal?.length()!! > 0.999f },
                frenetFrames)
        assertEquals(frenetFrames.filter { it.tangent.length() < 1.001f && it.tangent.length() > 0.999f },
                frenetFrames)
    }

    /**
     * Tests if the baseShape throws an exception if the number of points in different baseShapes differ.s
     */
    @Test
    fun testDrawSpline() {
        logger.info("This is the test for the CurveGeometry.")
        val point1 = Random.random3DVectorFromRange(-30f, -10f)
        val point2 = Random.random3DVectorFromRange(-9f, 20f)
        val point3 = Random.random3DVectorFromRange(21f, 30f)
        val point4 = Random.random3DVectorFromRange(31f, 100f)

        val controlPoints = arrayListOf(point1, point2, point3, point4)

        val curve = CatmullRomSpline(controlPoints)

        /*
        For this baseShape function the number of points may differ each time
        the baseShape function is invoked. However, the algorithm for calculating
        the triangles only works if the number of points of the baseShape is constant
        over the curve: the function should throw an error.
         */
        fun triangleFalse(): ArrayList<Vector3f> {
            val i = Random.randomFromRange(0.99f, 1.1f)
            val list = ArrayList<Vector3f>()
            list.add(Vector3f(0.3f, 0.3f, 0f))
            list.add(Vector3f(0.3f, -0.3f, 0f))
            list.add(Vector3f(-0.3f, -0.3f, 0f))
            return if(i >= 1 ) {
                list
            } else {
                list.add(Vector3f(0f, 0f, 0f))
                list
            }
        }

        assertFails {  Curve(curve) { triangleFalse() } }
    }

    /**
     * Tests if the curve works properly even with an empty spline.
     */
    @Test
    fun testEmptySpline() {
        logger.info("Tests the curve with an empty spline")
        val emptyList = ArrayList<Vector3f>()
        val spline = UniformBSpline(emptyList)
        fun triangle(): ArrayList<Vector3f> {
            val list = ArrayList<Vector3f>()
            list.add(Vector3f(0.3f, 0.3f, 0f))
            list.add(Vector3f(0.3f, -0.3f, 0f))
            list.add(Vector3f(-0.3f, -0.3f, 0f))
            return list
        }
        val emptyFloatBuffer = BufferUtils.createFloatBuffer(0)
        val curve = Curve(spline) { triangle() }
        assertEquals(curve.vertices, emptyFloatBuffer)
    }
}
