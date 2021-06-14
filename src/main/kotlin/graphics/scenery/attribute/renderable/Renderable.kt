package graphics.scenery.attribute.renderable

import graphics.scenery.Hub
import graphics.scenery.Node
import graphics.scenery.backends.Renderer
import java.util.*

/**
 * Generic interface for objects that can be rendered
 *
 * Matrices that are set to null shall be treated as identity matrix
 * by the renderer. See e.g. [projection] or [view].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
interface Renderable {

    // FIXME move to material
    /** Flag to set whether the object is a billboard and will always face the camera. */
    var isBillboard: Boolean


    var parent: Node

    /** Hash map used for storing metadata for the [Renderable]. [Renderer] implementations use
     * it to e.g. store renderer-specific state. */
    var metadata: HashMap<String, Any>

    /** Unique ID of the Renderable */
    fun getUuid(): UUID

    fun preUpdate(renderer: Renderer, hub: Hub?) {}

    /**
     * PreDraw function, to be called before the actual rendering, useful for
     * per-timestep preparation.
     */
    fun preDraw(): Boolean {
        return true
    }
}
