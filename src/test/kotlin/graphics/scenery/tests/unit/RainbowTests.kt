package graphics.scenery.tests.unit

import graphics.scenery.Mesh
import graphics.scenery.Rainbow
import org.joml.Vector3f
import org.junit.Test
import kotlin.test.assertEquals

/**
 * This is the Test for the Rainbow class.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 */
class RainbowTests {

    /**
     * Tests if the rainbow colors are assigned correctly.
     */
    @Test
    fun rainbowTest() {
        val mesh = Mesh("Parent")
        val child = Mesh("Child1")
        mesh.addChild(child)
        for(i in 1 .. 7) {
            child.addChild(Mesh("Child$i"))
        }
        val rainbow = Rainbow()
        rainbow.colorVector(mesh)
        val rainbowPaletteNotScaled = listOf(Vector3f(255f, 0f, 0f), Vector3f(255f, 165f, 0f),
            Vector3f(255f, 255f, 0f), Vector3f(0f, 128f, 0f), Vector3f(0f, 0f, 255f),
            Vector3f(75f, 0f, 135f), Vector3f(238f, 130f, 238f))
        val rainbowPalette = rainbowPaletteNotScaled.map { it.mul(1/255f) }
        val children = child.children
        for(j in 0 until 6) {
            assertEquals(children[j].material.diffuse, rainbowPalette[j])
        }
    }
}
