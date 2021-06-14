package graphics.scenery.attribute.spatial

import graphics.scenery.Node

interface HasCustomSpatial<T: Spatial>: Node {

    fun createSpatial(): T

    fun addSpatial() {
        addAttribute(Spatial::class.java, createSpatial())
    }

    fun addSpatial(block: T.() -> Unit) {
        addAttribute(Spatial::class.java, createSpatial(), block)
    }

    fun spatial(block: T.() -> Unit): T {
        val prop = this.spatial()
        prop.block()
        return prop
    }

    fun spatial(): T {
        return getAttribute(Spatial::class.java)
    }

    override fun ifSpatial(block: Spatial.() -> Unit): T? {
        return this.spatial(block)
    }

    override fun spatialOrNull(): T? {
        return this.spatial()
    }
}
