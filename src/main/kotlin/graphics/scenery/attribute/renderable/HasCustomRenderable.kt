package graphics.scenery.attribute.renderable

import graphics.scenery.Node

interface HasCustomRenderable<T: Renderable>: Node {

    fun createRenderable(): T

    fun addRenderable() {
        addAttribute(Renderable::class.java, createRenderable())
    }

    fun addRenderable(block: T.() -> Unit) {
        addAttribute(Renderable::class.java, createRenderable(), block)
    }

    fun renderable(block: T.() -> Unit): T {
        val prop = this.renderable()
        prop.block()
        return prop
    }

    fun renderable(): T {
        return getAttribute(Renderable::class.java)
    }

    override fun ifRenderable(block: Renderable.() -> Unit): T {
        return this.renderable(block)
    }

    override fun renderableOrNull(): T {
        return this.renderable()
    }
}
