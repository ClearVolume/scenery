package graphics.scenery.tests.unit

import graphics.scenery.PeriodicTable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [PeriodicTable]
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 */
class PeriodicTableTests {

    /**
     * Tests if findByElement finds hydrogen with elementNumber 1
     */
    @Test
    fun testHydrogen() {
        val table = PeriodicTable()
        val hydrogen = table.findElementByNumber(1)
        assertEquals(hydrogen.name, "Hydrogen")
        assertEquals(hydrogen.standardState, "Gas")
        assertEquals(hydrogen.atomicRadius, 120f)
    }

    /**
     * Tests if two periodic tables are being created equally
     */
    @Test
    fun testConsistency() {
        val table1 = PeriodicTable()
        val table2 = PeriodicTable()
        assertEquals(table1.elementList, table2.elementList)
    }

    /**
     * Tests if one of the missing parameters of oganesson becomes null.
     */
    @Test
    fun testOganesson() {
        val table = PeriodicTable()
        val oganesson = table.findElementByNumber(118)
        assertEquals(oganesson.symbol, "Og")
        assertNull(oganesson.boilingPoint)
    }

    /**
     * Tests if we fall back to hydrogen in case the given elementNumber is not in the table
     */
    @Test
    fun fallBackToHydrogen() {
        val table = PeriodicTable()
        val defaultHydrogen1 = table.findElementByNumber(0)
        val defaultHydrogen2 = table.findElementByNumber(119)
        val hydrogen = table.findElementByNumber(1)
        assertEquals(defaultHydrogen1.atomicMass, hydrogen.atomicMass)
        assertEquals(defaultHydrogen2.atomicMass, hydrogen.atomicMass)
    }

    /**
     * Tests if Plutonium is found by its symbol
     */
    @Test
    fun findPlutoniumBySymbol() {
        val table = PeriodicTable()
        val plutonium = table.findElementBySymbol("Pu")
        assertEquals(plutonium.atomicNumber, 94)
    }
}
