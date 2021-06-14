package graphics.scenery.attribute.geometry

import graphics.scenery.Node

interface DelegatesGeometry: Node {

    fun getDelegateGeometry(): Geometry?

    override fun ifGeometry(block: Geometry.() -> Unit): Geometry? {
        val delegateGeometry = getDelegateGeometry()
        delegateGeometry?.block()
        return delegateGeometry
    }

    @Throws(IllegalStateException::class)
    fun geometry(block: Geometry.() -> Unit): Geometry {
        val delegateGeometry = getDelegateGeometry()
            ?: throw IllegalStateException(name + ": delegates geometry properties, but the delegate is null")
        delegateGeometry.block()
        return delegateGeometry
    }
}
