package graphics.scenery.attribute.material

import graphics.scenery.Node

interface HasCustomMaterial<T: Material>: Node {

    fun createMaterial(): T

    fun addMaterial() {
        addAttribute(Material::class.java, createMaterial())
    }

    fun addMaterial(block: T.() -> Unit) {
        addAttribute(Material::class.java, createMaterial(), block)
    }

    fun material(block: T.() -> Unit): T {
        val prop = material()
        prop.block()
        return prop
    }

    fun material(): T {
        return getAttribute(Material::class.java)
    }

    override fun ifMaterial(block: Material.() -> Unit): T {
        return this.material(block)
    }

    override fun materialOrNull(): T {
        return this.material()
    }
}
