package graphics.scenery.volumes

import graphics.scenery.DefaultNode
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.extensions.xyz
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * Non-geometry node to handle slicing plane behavior.
 * Default position is the XZ-plane through the origin.
 */
class SlicingPlane(override var name: String = "Slicing Plane") : DefaultNode(name), HasSpatial {
    private var slicedVolumes = listOf<Volume>()

    init {
        addSpatial()
        postUpdate.add {

            if (slicedVolumes.isEmpty()) {
                return@add
            }
            // calculate closest point to origin in plane and use it as base for the plane equation
            val pn = spatial().world.transform(Vector4f(0f, 1f, 0f, 0f)).xyz().normalize()
            val p0 = spatial().worldPosition(Vector3f(0f))

            val nul = Vector3f(0f)
            val projectedNull = nul - (pn.dot(nul - p0)) * pn

            val planeEq =
                // If the origin is within the slicing plane use the normal of the plane with a w value of 0 instead
                if (projectedNull.equals(0f, 0f, 0f)) {
                    Vector4f(pn, 0f)
                } else {
                    // Negative w values invert the slicing decision in the shader.
                    // This is for cases where the slicing plane is "upside-down".
                    Vector4f(projectedNull, projectedNull.lengthSquared() * if (pn.dot(projectedNull) < 0) -1 else 1)
                }

            slicedVolumes.forEach { it.slicingPlaneEquations += this to planeEq }
        }
    }

    fun addTargetVolume(volume: Volume) {
        slicedVolumes = slicedVolumes + volume
    }

    fun removeTargetVolume(volume: Volume) {
        slicedVolumes = slicedVolumes - volume
        volume.slicingPlaneEquations = volume.slicingPlaneEquations.minus(this)
    }

}
