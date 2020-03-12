package graphics.scenery.tests.unit

import cleargl.GLVector
import graphics.scenery.numerics.Random
import graphics.scenery.CatmullRomSpline
import graphics.scenery.CurveGeometry
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

/**
 * This is the test class for the [CurveGeometry]
 *
 * @author Justin Bürger, burger@mpi-cbg.com
 */
class CurveGeometryTests {
    private val logger by LazyLogger()

    /**
     * Tests if the vectors are normalized and if the bitangent and normal vector are becoming null.
     * Moreover it asserts that a erroneous baseShape function leads to an exception.
     */
    @Test
    fun testCreation() {
        logger.info("This is the test for the CurveGeometry.")
        val point1 = Random.randomVectorFromRange(3, -30f, -10f)
        val point2 = Random.randomVectorFromRange(3, -9f, 20f)
        val point3 = Random.randomVectorFromRange(3, 21f, 30f)
        val point4 = Random.randomVectorFromRange(3, 31f, 100f)

        val controlPoints = arrayListOf(point1, point2, point3, point4)

        val curve = CatmullRomSpline(controlPoints)

        val geometry = CurveGeometry(curve)
        val frenetFrames = geometry.computeFrenetFrames(geometry.getCurve())

        /*
        For this baseShape function the number of points may differ each time
        the baseShape function is invoked. However, the algorithm for calculating
        the triangles only works if the number of points of the baseShape is constant
        over the curve: the function should throw an error.
         */
        fun triangleFalse(): ArrayList<GLVector> {
            val i = Random.randomFromRange(0.99f, 1.1f)
            val list = ArrayList<GLVector>()
            list.add(GLVector(0.3f, 0.3f, 0f))
            list.add(GLVector(0.3f, -0.3f, 0f))
            list.add(GLVector(-0.3f, -0.3f, 0f))
            return if(i >= 1 ) {
                list
            } else {
                list.add(GLVector(0f, 0f, 0f))
                list
            }
        }

        assertEquals(curve.catMullRomChain(), geometry.getCurve())
        assertNotNull(frenetFrames.forEach { it.normal })
        assertNotNull(frenetFrames.forEach{ it.bitangent })
        assertEquals(frenetFrames.filter { it.bitangent?.length2()!! < 1.0001f && it.bitangent?.length2()!! > 0.99999f },
                frenetFrames)
        assertEquals(frenetFrames.filter { it.normal?.length2()!! < 1.0001f && it.normal?.length2()!! > 0.99999f },
                frenetFrames)
        assertEquals(frenetFrames.filter { it.tangent.length2() < 1.0001f && it.tangent.length2() > 0.99999f },
                frenetFrames)
        assertFails {  geometry.drawSpline { triangleFalse() } }
    }

}
