package graphics.scenery

import graphics.scenery.attribute.DelegatesProperties
import graphics.scenery.attribute.DelegationType
import graphics.scenery.attribute.geometry.DelegatesGeometry
import graphics.scenery.attribute.geometry.Geometry
import graphics.scenery.attribute.material.DelegatesMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.renderable.DelegatesRenderable
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.renderable.Renderable
import graphics.scenery.attribute.spatial.HasSpatial
import org.joml.Matrix4f
import java.util.concurrent.CopyOnWriteArrayList

open class InstancedNode(template: Node, override var name: String = "InstancedNode") : DefaultNode(name), DelegatesProperties, DelegatesRenderable,
    DelegatesGeometry, DelegatesMaterial {
    /** instances */
    val instances = CopyOnWriteArrayList<Instance>()
    /** instanced properties */
    val instancedProperties = LinkedHashMap<String, () -> Any>()
    private val delegationType: DelegationType = DelegationType.ForEachNode
    override fun getDelegationType(): DelegationType {
        return delegationType
    }

    var template: Node? = null
        set(node) {
//            val instancedMaterial = ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")
//            node?.renderable {
//                material = instancedMaterial
//            }
            instancedProperties.put("ModelMatrix", { node?.spatialOrNull()?.model ?: Matrix4f() })
            field = node
        }
    //    val updateStrategy = // TODO make enum for different strategies -> one time, every second (or fixed time interval), each frame

    init {
        this.template = template
        boundingBox = template.generateBoundingBox()
    }

    override fun getDelegateRenderable(): Renderable? {
        return template?.renderableOrNull()
    }

    override fun getDelegateMaterial(): Material? {
        return template?.materialOrNull()
    }

    override fun getDelegateGeometry(): Geometry? {
        return template?.geometryOrNull()
    }

    fun addInstance(): Instance {
        val node = Instance(this)
        node.instancedProperties.put("ModelMatrix", { node.spatial().world })
        node.boundingBox = node.generateBoundingBox()
        instances.add(node)
        return node
    }

    override fun generateBoundingBox(): OrientedBoundingBox? {
        //TODO? generate joint boundingbox of all instances, set bounding box
        return template?.generateBoundingBox()
    }

    class Instance(val instancedParent : InstancedNode, override var name: String = "Instance") : DefaultNode(name),
        HasRenderable, HasSpatial {
        var instancedProperties = LinkedHashMap<String, () -> Any>()

        init {
            addRenderable()
            addSpatial()
        }

        override fun generateBoundingBox(): OrientedBoundingBox? {
            return instancedParent.template?.generateBoundingBox()
        }
    }
}
