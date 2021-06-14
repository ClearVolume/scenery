package graphics.scenery.attribute.material

import graphics.scenery.Node

/**
 * Node type that enables delegation of rendering. For rendering, not the node itself will be drawn,
 * but the node referred as [delegateRendering]. A [delegationType] can be selected to choose whether the delegate
 * will be drawn for all nodes that refer to it, or only once.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
interface DelegatesMaterial: Node {

    fun getDelegateMaterial(): Material?

    override fun ifMaterial(block: Material.() -> Unit): Material? {
        val delegateMaterial = getDelegateMaterial()
        delegateMaterial?.block()
        return delegateMaterial
    }

    @Throws(IllegalStateException::class)
    fun material(block: Material.() -> Unit): Material {
        val delegateMaterial = getDelegateMaterial()
            ?: throw IllegalStateException(name + ": delegates material properties, but the delegate is null")
        delegateMaterial.block()
        return delegateMaterial
    }
}
