package graphics.scenery

import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.spatial.HasSpatial

/**
 * Node to group other Nodes together. This is just a convenience class,
 * that -- apart from its name -- does not provide additional functionality.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class Group : DefaultNode("Group"), HasSpatial, HasRenderable, HasMaterial {
    init {
        addSpatial()
        addRenderable()
        addMaterial()
    }
}
