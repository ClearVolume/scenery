package graphics.scenery.attribute.renderable

import graphics.scenery.Node

/**
 * Node type that enables delegation of rendering. For rendering, not the node itself will be drawn,
 * but the node referred as [delegateRenderable]. A [delegationType] can be selected to choose whether the delegate
 * will be drawn for all nodes that refer to it, or only once.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
interface DelegatesRenderable: Node {

    fun getDelegateRenderable(): Renderable?

    override fun ifRenderable(block: Renderable.() -> Unit): Renderable? {
        val delegateRenderable = getDelegateRenderable()
        delegateRenderable?.block()
        return delegateRenderable
    }

    @Throws(IllegalStateException::class)
    fun renderable(block: Renderable.() -> Unit): Renderable {
        val delegateRenderable = getDelegateRenderable()
            ?: throw IllegalStateException(name + ": delegates renderable properties, but the delegate is null")
        delegateRenderable.block()
        return delegateRenderable
    }
}
