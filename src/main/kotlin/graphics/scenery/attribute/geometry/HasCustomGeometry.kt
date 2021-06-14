package graphics.scenery.attribute.geometry

import graphics.scenery.Node

interface HasCustomGeometry<T: Geometry>: Node {

    fun createGeometry(): T

    fun addGeometry() {
        addAttribute(Geometry::class.java, createGeometry())
    }

    fun addGeometry(block: T.() -> Unit) {
        addAttribute(Geometry::class.java, createGeometry(), block)
    }

    fun geometry(block: T.() -> Unit): T {
        val props = geometry()
        props.block()
        return props
    }

    fun geometry(): T {
        return getAttribute(Geometry::class.java)
    }

    override fun ifGeometry(block: Geometry.() -> Unit): T {
        return this.geometry(block)
    }

    override fun geometryOrNull(): T {
        return this.geometry()
    }
}
