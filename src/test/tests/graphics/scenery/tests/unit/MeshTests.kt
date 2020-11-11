package graphics.scenery.tests.unit

import graphics.scenery.mesh.Mesh
import graphics.scenery.mesh.MeshImporter
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for the [Mesh] class.
 *
 * @author Aryaman Gupta <aryaman1994@gmail.com>
 */
class MeshTests {
    private val logger by LazyLogger()

    /**
     * Tests reading a [Mesh] from an OBJ file and verifies correct input and
     * bounding box creation.
     */
    @Test
    fun testReadFromOBJ() {
        logger.info("Testing mesh creation from OBJ file and bounding box calculation ...")
        val erythrocyte = MeshImporter.readFrom(Mesh::class.java.getResource("models/erythrocyte.obj").file)

        assertEquals(18000, erythrocyte.vertices.capacity())
        assertEquals(18000, erythrocyte.normals.capacity())
        assertEquals(3000, erythrocyte.indices.capacity())
        assertEquals(12000, erythrocyte.texcoords.capacity())

        val erythrocyteBoundingBox = erythrocyte.boundingBox
        assertNotNull(erythrocyteBoundingBox)

        assertEquals(-2.820594f, erythrocyteBoundingBox.min.x())
        assertEquals(-1.032556f, erythrocyteBoundingBox.min.y())
        assertEquals(-2.784652f, erythrocyteBoundingBox.min.z())

        assertEquals(2.825022f, erythrocyteBoundingBox.max.x())
        assertEquals(2.788384f, erythrocyteBoundingBox.max.y())
        assertEquals(1.63499f, erythrocyteBoundingBox.max.z())


        val leukocyte = MeshImporter.readFrom(Mesh::class.java.getResource("models/leukocyte.obj").file)

        assertEquals(144000, leukocyte.vertices.capacity())
        assertEquals(144000, leukocyte.normals.capacity())
        assertEquals(24000, leukocyte.indices.capacity())
        assertEquals(96000, leukocyte.texcoords.capacity())

        val leukocyteBoundingBox = leukocyte.boundingBox
        assertNotNull(leukocyteBoundingBox)

        assertEquals(-1.121471f, leukocyteBoundingBox.min.x())
        assertEquals(-1.09877f, leukocyteBoundingBox.min.y())
        assertEquals(-1.087235f, leukocyteBoundingBox.min.z())

        assertEquals(1.121211f, leukocyteBoundingBox.max.x())
        assertEquals(1.096298f, leukocyteBoundingBox.max.y())
        assertEquals(1.109781f, leukocyteBoundingBox.max.z())
    }

    /**
     * Tests reading a [Mesh] from an STL file and verifies correct input and
     * bounding box creation.
     */
    @Test
    fun testReadFromBinarySTL() {
        logger.info("Testing mesh creation from STL file and bounding box calculation ...")
        val erythrocyte = MeshImporter.readFrom(Mesh::class.java.getResource("models/erythrocyte.stl").file)

        assertEquals(9000, erythrocyte.vertices.capacity())
        assertEquals(9000, erythrocyte.normals.capacity())
        /*TODO:indices and texcoords?*/

        val boundingBox = erythrocyte.boundingBox
        assertNotNull(boundingBox)

        assertEquals(-2.820594f, boundingBox.min.x())
        assertEquals(-1.032556f, boundingBox.min.y())
        assertEquals(-2.784652f, boundingBox.min.z())

        assertEquals(2.825022f, boundingBox.max.x())
        assertEquals(2.788384f, boundingBox.max.y())
        assertEquals(1.63499f, boundingBox.max.z())
    }

    /**
     * Tests reading a [Mesh] from an STL file and verifies correct input and
     * bounding box creation.
     */
    @Test
    fun testReadFromASCIISTL() {
        logger.info("Testing mesh creation from STL file and bounding box calculation ...")
        val erythrocyte = MeshImporter.readFrom(Mesh::class.java.getResource("models/erythrocyte_ascii.stl").file)

        assertEquals(9000, erythrocyte.vertices.capacity())
        assertEquals(9000, erythrocyte.normals.capacity())
        /*TODO:indices and texcoords?*/

        val boundingBox = erythrocyte.boundingBox
        assertNotNull(boundingBox)

        assertEquals(-2.820594f, boundingBox.min.x())
        assertEquals(-1.032556f, boundingBox.min.y())
        assertEquals(-2.784652f, boundingBox.min.z())

        assertEquals(2.825022f, boundingBox.max.x())
        assertEquals(2.788384f, boundingBox.max.y())
        assertEquals(1.63499f, boundingBox.max.z())
    }
}
