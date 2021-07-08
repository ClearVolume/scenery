package graphics.scenery

import org.joml.*
import graphics.scenery.PeriodicTable
import org.biojava.nbio.structure.*

/**
 * This is a representation of a protein structure. Displayed are the covalent bonds of the protein with atoms being
 * represented with Icospheres and cylinders for the connections between them.
 * @param [protein] Protein which is to be visualized.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class StickAndBallProteinModel(protein: Protein, spaceFilling: Boolean = false,
                               displayExternalMolecules: Boolean = false): Mesh("PrimaryStructure") {
    val structure = protein.structure
    companion object PerTab { private val periodicTable = PeriodicTable() }

    init {

        val atoms: Array<Atom> = StructureTools.getAllAtomArray(structure)

        val atomMasters = HashMap<Element, Node>()

        enumValues<Element>().forEach {
            val sceneryElement = periodicTable.findElementByNumber(it.atomicNumber)
             val s = if(spaceFilling) {
                        if(sceneryElement.atomicRadius != null) {
                            Icosphere(sceneryElement.atomicRadius/100f, 2)
                        }
                        else {
                            Icosphere(0.05f, 2)
                        }
                    }
                    else {  Icosphere(0.05f, 2) }
            s.material = ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")
            if (sceneryElement.color != null) {
                s.material.diffuse = sceneryElement.color
                //s.material.ambient = element.color
                //s.material.specular = element.color
            }
            s.instancedProperties["ModelMatrix"] = { s.world }
            atomMasters[it] = s
        }

        atoms.filter {
            if(displayExternalMolecules) {
                true
            }
            else { it.group.hasAminoAtoms() }
        }.forEach {
            val element = periodicTable.findElementByNumber(it.element.atomicNumber)
            val s = Node()
            s.position = (Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()))
            s.instancedProperties["ModelMatrix"] = { s.world }
            if (element.color != null) {
                s.material.diffuse = element.color
                //s.material.ambient = element.color
                //s.material.specular = element.color
            }
            val master = atomMasters[it.element]
            master?.instances?.add(s)
        }

        atomMasters.filter { it.value.instances.isNotEmpty() }
            .forEach { this.addChild(it.value) }

        if (!spaceFilling) {
            val c = Cylinder(0.025f, 1.0f, 10)
            c.material = ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")
            c.instancedProperties["ModelMatrix1"] = { c.model }
            c.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f)

            val bonds: MutableList<Bond> = atoms.filter { it.bonds != null }.flatMap { it.bonds }.toMutableList()

            val aminoList = AminoList()
            val chains = structure.chains
            val groups = chains.flatMap { it.atomGroups }

            //This creates bonds for all the amino acids stored in the pdb
            aminoList.forEach { residue ->
                val name = residue.name
                //please note that these bonds are not the bonds stored in the pdb-file but the hardcoded bonds from the AminoList
                val bondList = residue.bonds
                groups.forEach { group ->
                    if (group.pdbName == name) {
                        bondList.forEach { triple ->
                            group.atoms.forEach { atom1 ->
                                group.atoms.forEach { atom2 ->
                                    if ((atom1.name + "'") == triple.first && (atom2.name + "'") == triple.second) {
                                        val bond = BondImpl(atom1, atom2, triple.third)
                                        bonds.add(bond)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            //computes the bonds between amino acids
            chains.forEach { chain ->
                chain.atomGroups.windowed(2, 1) {
                    val group1 = it[0]
                    val group2 = it[1]
                    group1.atoms.forEach { atom1 ->
                        group2.atoms.forEach { atom2 ->
                            if (atom1.name == "CA" && atom2.name == "N") {
                                val bond = BondImpl(atom1, atom2, 1)
                                bonds.add(bond)
                            }
                        }
                    }
                }
            }

            val cylinders = bonds.map {
                val bond = Mesh()
                bond.parent = this
                val atomA = it.atomA
                val atomB = it.atomB
                bond.orientBetweenPoints(
                    Vector3f(atomA.x.toFloat(), atomA.y.toFloat(), atomA.z.toFloat()),
                    Vector3f(atomB.x.toFloat(), atomB.y.toFloat(), atomB.z.toFloat()), true, true
                )
                bond.instancedProperties["ModelMatrix1"] = { bond.model }
                bond
            }
            c.instances.addAll(cylinders)

            this.addChild(c)
        }
    }
}
