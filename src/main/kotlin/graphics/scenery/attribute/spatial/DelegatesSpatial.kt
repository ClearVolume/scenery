package graphics.scenery.attribute.spatial

import graphics.scenery.Node

interface DelegatesSpatial: Node {

    fun getDelegateSpatial(): Spatial?

    override fun ifSpatial(block: Spatial.() -> Unit): Spatial? {
        val delegateSpatial = getDelegateSpatial()
        delegateSpatial?.block()
        return delegateSpatial
    }

    @Throws(IllegalStateException::class)
    fun spatial(block: Spatial.() -> Unit): Spatial {
        val delegateSpatial = getDelegateSpatial()
            ?: throw IllegalStateException(name + ": delegates spatial properties, but the delegate is null")
        delegateSpatial.block()
        return delegateSpatial
    }
}
