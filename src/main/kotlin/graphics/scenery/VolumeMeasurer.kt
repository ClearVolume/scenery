package graphics.scenery

import graphics.scenery.geometry.GeometryType
import graphics.scenery.utils.LazyLogger
import kotlin.math.absoluteValue

/**
 * This class contains the functionality of measuring the volume of a mesh. The algorithm is taken from:
 * EFFICIENT FEATURE EXTRACTION FOR 2D/3D OBJECTS IN MESH REPRESENTATION by Cha Zhang and Tsuhan Cheng
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class VolumeMeasurer {
    val logger by LazyLogger()

    /**
     * calculates the volume of a given [mesh] and returns its volume with double precision
     */
    fun calculateVolume(mesh: Mesh): Double {
        val vertices = mesh.vertices
        val vertexBuffer = vertices.asReadOnlyBuffer()
        if(vertexBuffer.limit() == 0) {
            logger.info("The vertex buffer of the mesh provided for the volume-measurement is empty")
        }
        val arraySize = vertexBuffer.limit()
        val numberOfSubVolumes = arraySize%9
        val subVolumes = ArrayList<Float>(numberOfSubVolumes)
        var i = 0
        while (i < arraySize) {
            val x1 = vertexBuffer.get(i)
            val y1 = vertexBuffer.get(i+1)
            val z1 = vertexBuffer.get(i+2)
            val x3 = vertexBuffer.get(i+3)
            val y3 = vertexBuffer.get(i+4)
            val z3 = vertexBuffer.get(i+5)
            val x2 = vertexBuffer.get(i+6)
            val y2 = vertexBuffer.get(i+7)
            val z2 = vertexBuffer.get(i+8)
            val i1 = (x3 * y2 * z1)
            val i2 = (x2 * y3 * z1)
            val i3 = (x3 * y1 * z2)
            val i4 = (x1 * y3 * z2)
            val i5 = (x2 * y1 * z3)
            val i6 = (x1 * y2 * z3)
            //The absolute value is not needed for the original algorithm, however,
            //the clockwise ordering in triangle strips makes it necessary.
            val volume = ((-i1 + i2 + i3 - i4 - i5 + i6)/6f).absoluteValue
            subVolumes.add(volume)
            if(arraySize%9 == 0 && mesh.geometry().geometryType == GeometryType.TRIANGLES) {
                i += 9
            }
            else if (arraySize%3 == 0 && mesh.geometry().geometryType == GeometryType.TRIANGLE_STRIP) {
                if(i < arraySize-9) {
                    i += 3
                }
                else { break }
            }
            else { break }
        }
        return (subVolumes.fold(0.0) {acc, next -> acc + next }.absoluteValue)
    }
}
