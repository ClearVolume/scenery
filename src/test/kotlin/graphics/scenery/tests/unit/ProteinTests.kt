package graphics.scenery.tests.unit

import graphics.scenery.*
import graphics.scenery.utils.LazyLogger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Unit test for the Protein class
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class ProteinTests {
    private val logger by LazyLogger()

    @Test
    fun testInvalidPath() {
        logger.info("Tests that an invalid path gets caught")
        assertFails { Protein.fromFile("LetsGetShwifty") }
    }

    @Test
    fun testInvalidID() {
        logger.info("Test if an invalid pdb entry is caught")
        assertFails { Protein.fromID("3mbn") }
    }

    @Test
    fun testOpenFromFile() {
        logger.info("Test if opening from file works")
        assertEquals(Protein.fromFile(
            "src/test/resources/graphics/scenery/tests/unit/proteins/2zzw.pdb").structure.pdbCode, "2ZZW")
    }
}
