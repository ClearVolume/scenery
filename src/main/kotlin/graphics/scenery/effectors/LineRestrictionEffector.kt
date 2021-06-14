package graphics.scenery.effectors

import graphics.scenery.Node
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Volume
import org.joml.Vector3f

/**
 * Adds an update function to the target which restricts its local position to be between [from] and [to]
 *
 * @author Jan Tiemann
 */
open class LineRestrictionEffector(val target: Node, var from: () -> Vector3f, var to: () -> Vector3f) {
    /** Whether this effector node is currently active */
    var active: Boolean = true

    init {
        target.update.add {
            if (!active){
                return@add
            }
            target.ifSpatial {
                val a = from()
                val b = to()

                val p = position

                val ab = b - a
                val ap = p - a

                val dot = ap.dot(ab)

                if (dot <= 0) {
                    position = a
                    return@ifSpatial
                }

                val pDotDir = ab * (dot / ab.lengthSquared())

                if (pDotDir.lengthSquared() > ab.lengthSquared()) {
                    position = b
                } else {
                    position = a + pDotDir
                }
            }
        }
    }
}
