package graphics.scenery

import org.joml.Vector2i

/**
 * Interface for rendering a node that requires scissoring
 *
 * @author Ulrik Guenther
 * @author Giuseppe Barbieri
 */
interface RenderScissored {
    var offset: Vector2i
    var extent: Vector2i
}