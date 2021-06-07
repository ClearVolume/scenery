package graphics.scenery

/**
 * Represents an Amino acid with a String as its name and a list of its bonds (which have the following data structure:
 * Triple(atom1_name, atom2_name, bond_order))
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
data class AminoAcid (val name: String, val bonds: List<Triple<String, String, Int>>)
