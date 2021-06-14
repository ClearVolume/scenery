package graphics.scenery.effectors

import graphics.scenery.*
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.volumes.Volume

/**
 * Volume effector class
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
open class VolumeEffector : DefaultNode("VolumeEffector"), HasRenderable, HasMaterial, HasSpatial {
    /** Whether this effector node is currently active */
    var active: Boolean = false
        private set

    /** The volume this effector is associated with */
    var activeVolume: Node? = null
        private set

    /** Proxy node to display e.g. auxiliary geometry. */
    open var proxy : Node = DefaultNode("proxy")

    init {
        addRenderable()
        addMaterial()
        addSpatial()
        update.add {
            getScene()?.let {
                it.discover(it, { it is Volume }).forEach { node ->
                    if (proxy.spatialOrNull()?.intersects(node) == true) {
                        activeVolume = node
                    }
                }
            }
        }
    }
}
