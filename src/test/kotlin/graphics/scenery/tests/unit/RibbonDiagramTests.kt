package graphics.scenery.tests.unit

import graphics.scenery.*
import graphics.scenery.utils.LazyLogger
import org.biojava.nbio.structure.Group
import org.biojava.nbio.structure.secstruc.SecStrucElement
import org.joml.Vector3f
import org.junit.Test
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * This is the test for the RibbonCalculation, i.e. the pdb-support.
 *
 * @author Justin Buerger, burger@mpi-cbg.com
 */
class RibbonDiagramTests {
    private val logger by LazyLogger()

    /**
     * Tests coherence of curve size and number of residues.
     */
    @Test
    fun residueCountTest() {
        logger.info("Tests coherence of curve size and number of residues.")
        val plantProtein = Protein.fromID("3nir")
        val plantRibbon = RibbonDiagram(plantProtein)
        val dsspPlant = plantRibbon.callPrivateFunc("dssp")
        val plantChains = plantProtein.getResidues()
        var allPlantPoints = 0
        plantChains.forEach {
            if(dsspPlant is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val guides = RibbonDiagram.GuidePointCalculation.calculateGuidePoints(it, dsspPlant as List<SecStrucElement>)
                val spline = plantRibbon.callPrivateFunc("ribbonSpline", guides) as DummySpline
                allPlantPoints += spline.splinePoints().size
            }
        }
        assertEquals(allPlantPoints, (46)*(10+1))

        val saccharomycesCerevisiae = Protein.fromID("6zqd")
        val scRibbon = RibbonDiagram(saccharomycesCerevisiae)
        val dsspSC = scRibbon.callPrivateFunc("dssp")
        val scChains = saccharomycesCerevisiae.getResidues()
        var allSCPoints = 0
        scChains.forEach {
            if(dsspSC is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val guides = RibbonDiagram.GuidePointCalculation.calculateGuidePoints(it, dsspSC as List<SecStrucElement>)
                val spline = scRibbon.callPrivateFunc("ribbonSpline", guides) as DummySpline
                allSCPoints += spline.splinePoints().size
            }
        }
        assertEquals(allSCPoints, (23448)*(10+1))

    }

    /**
     * Tests number of subProteins.
     */
    @Test
    fun numberSubProteinsTest() {
        logger.info("Tests number of subProteins.")
        val plantProtein = Protein.fromID("3nir")
        val plantRibbon = RibbonDiagram(plantProtein)
        assertEquals(plantRibbon.children.size, 1)

        val insectWing = Protein.fromID("2w49")
        val insectWingRibbon = RibbonDiagram(insectWing)
        assertEquals(insectWingRibbon.children.size, 36)

        /*
        val saccharomycesCerevisiae = Protein.fromID("6zqd")
        val scRibbon = RibbonDiagram(saccharomycesCerevisiae)
        assertEquals(scRibbon.children.size, 63)
        */

        /*
        val covid19 = Protein.fromID("6zcz")
        val covidRibbon = RibbonDiagram(covid19)
        assertEquals(covidRibbon.children.size, 4)
         */

        val aspirin = Protein.fromID("6mqf")
        val aspirinRibbon = RibbonDiagram(aspirin)
        assertEquals(aspirinRibbon.children.size, 2)

        val nucleosome = Protein.fromID("6y5e")
        val nucRibbon = RibbonDiagram(nucleosome)
        assertEquals(nucRibbon.children.size, 9)
    }

    /**
     * Tests a lot of pdb structures and check that everyone of them yields a valid output.
     * The comments refer to the chosen filter in the "SCIENTIFIC NAME OF SOURCE ORGANISM" category
     * in the RCSB data base. These organisms as well as the pdb files have been chosen arbitrary.
     * The null checks are there to satisfy the test structure- the test verifies in fact that no
     * exception is thrown.
     */
    @Test
    fun testLotsOfProteins() {
        val proteins = listOf(
            "6l69", "3mbw", "4u1a", "5m9m", "6mzl", "6mp5", "2qd4", "6pe9",
            "1ydk", "2rma", "3mdc", "2kne", "4tn7", "3mao", "5m8s", "6v2e",
            "4giz", "3l2j", "4odq", "6slm", "2qho", "1zr0", "2ake", "2wx1",
            "2mue", "2m0j", "1q5w", "3gj8", "3sui", "6pby", "2m0k", "1r4a",
            "3fub", "6uku", "6v92", "2l2i", "1pyo", "4lcd", "6p9x", "6uun",
            "6v80", "6v7z", "4grw", "3mc5", "3mbw", "4tkw", "4u0i", "3mas",
            "6znn", "1ctp", "3j92", "3jak", "1nb5", "3lk3", "1mdu", "3eks",
            "2ebv", "4gbj", "6v4e", "6v4h", "4m8n", "4ia1", "3ei2", "2rh1",
            "6ps3", "3v2y", "4pla", "3eml", "2seb", "2qej", "1d5m", "2wy8",
            "4idj", "2vr3", "2win", "6urh", "3ua7", "3mrn", "4z0x", "2rhk",
            "6pdx", "6urm", "2x4q", "1r0n", "2ff6", "4i7b", "3bs5", "5chl",
            "5f84", "4uuz", "4v98", "4wsi", "4u68", "4aa1", "5jvs", "6hom",
            "4xib", "4u0q", "6phf")

        proteins.shuffled().drop(80).forEach { pdbId ->
            val protein = Protein.fromID(pdbId)
            logger.info("Testing ${protein.structure.name} ...")
            RibbonDiagram(protein)
        }

    }

    @Test
    fun testSplinePointsToResidueNumber() {
        val protein = Protein.fromID("4u68")
        val ribbon = RibbonDiagram(protein)
        val residuesCount = ribbon.children.flatMap { chain -> chain.children }.flatMap { it.children }.size
        val allResidues = protein.structure.chains.flatMap { it.atomGroups }.filter { it.hasAminoAtoms() }
        assertEquals(residuesCount, allResidues.size)
    }

}

//Inline function for the protein to access residues
private fun Protein.getResidues(): ArrayList<ArrayList<Group>> {
    val proteins = ArrayList<ArrayList<Group>>(this.structure.chains.size)
    this.structure.chains.forEach{ chain ->
        if(chain.isProtein) {
            val aminoList = ArrayList<Group>(chain.atomGroups.size)
            chain.atomGroups.forEach { group ->
                if (group.hasAminoAtoms()) {
                    aminoList.add(group)
                }
            }
            proteins.add(aminoList)
        }
    }
    return proteins
}

//Inline function to access private function in the RibbonDiagram
private inline fun <reified T> T.callPrivateFunc(name: String, vararg args: Any?): Any? =
        T::class
                .declaredMemberFunctions
                .firstOrNull { it.name == name }
                ?.apply { isAccessible = true }
                ?.call(this, *args)
