package graphics.scenery

import graphics.scenery.geometry.GeometryType
import graphics.scenery.attribute.AttributesMap
import graphics.scenery.attribute.geometry.Geometry
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.renderable.Renderable
import graphics.scenery.attribute.spatial.Spatial
import graphics.scenery.utils.MaybeIntersects
import net.imglib2.Localizable
import net.imglib2.RealLocalizable
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.Serializable
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.collections.ArrayList

interface Node : Serializable {
    var name: String
    var nodeType: String
    /** Children of the Node. */
    var children: CopyOnWriteArrayList<Node>
    /** Other nodes that have linked transforms. */
    var linkedNodes: CopyOnWriteArrayList<Node>
    /** Parent node of this node. */
    var parent: Node?
    /** Creation timestamp of the node. */
    var createdAt: Long
    /** Modification timestamp of the node. */
    var modifiedAt: Long
    /** Flag to set whether the object is visible or not. */
    var visible: Boolean
    var discoveryBarrier: Boolean
    /** Hash map used for storing metadata for the Node. */
    var metadata: HashMap<String, Any>
    /** Node update routine, called before updateWorld */
    var update: ArrayList<() -> Unit>
    /** Node update routine, called after updateWorld */
    var postUpdate: ArrayList<() -> Unit>
    /** Whether the object has been initialized. Used by renderers. */
    var initialized: Boolean
    /** State of the Node **/
    var state: State
    /** [ReentrantLock] to be used if the object is being updated and should not be
     * touched in the meantime. */
    var lock: ReentrantLock
    /** Initialisation function for the object */
    fun init(): Boolean

    val logger: org.slf4j.Logger

    /** bounding box **/
    var boundingBox: OrientedBoundingBox?

    fun getAttributes(): AttributesMap

    fun <T, U: T> getAttributeOrNull(attributeType: Class<T>): U? {
        return getAttributes().get<T, U>(attributeType)
    }

    fun <T, U: T> addAttribute(attributeType: Class<T>, attribute: U) {
        getAttributes().put(attributeType, attribute)
    }

    fun <T, U: T> addAttribute(attributeType: Class<T>, attribute: U, block: U.() -> Unit) {
        attribute.block()
        addAttribute(attributeType, attribute)
    }

    fun <T, U: T> ifHasAttribute(attributeType: Class<T>, block: U.() -> Unit) : U? {
        val attribute = getAttributeOrNull<T, U>(attributeType) ?: return null
        attribute.block()
        return attribute
    }

    @Throws(IllegalStateException::class)
    fun <T, U: T> getAttribute(attributeType: Class<T>, block: U.() -> Unit) : U {
        val attribute = getAttribute<T, U>(attributeType)
        attribute.block()
        return attribute
    }

    @Throws(IllegalStateException::class)
    fun <T, U: T> getAttribute(attributeType: Class<T>) : U {
        return getAttributeOrNull<T, U>(attributeType) ?: throw IllegalStateException("Node doesn't have attribute named " + attributeType)
    }

    fun ifSpatial(block: Spatial.() -> Unit): Spatial? {
        return ifHasAttribute(Spatial::class.java, block)
    }

    fun spatialOrNull(): Spatial? {
        return ifSpatial {}
    }

    fun ifGeometry(block: Geometry.() -> Unit): Geometry? {
        return ifHasAttribute(Geometry::class.java, block)
    }

    fun geometryOrNull(): Geometry? {
        return ifGeometry {}
    }

    fun ifRenderable(block: Renderable.() -> Unit): Renderable? {
        return ifHasAttribute(Renderable::class.java, block)
    }

    fun renderableOrNull(): Renderable? {
        return ifRenderable {}
    }

    fun ifMaterial(block: Material.() -> Unit): Material? {
        return ifHasAttribute(Material::class.java, block)
    }

    fun materialOrNull(): Material? {
        return ifMaterial {}
    }

    fun setMaterial(material: Material) {
        return addAttribute(Material::class.java, material)
    }

    fun setMaterial(material: Material, block: Material.() -> Unit) {
        return addAttribute(Material::class.java, material, block)
    }

    /** Unique ID of the Node */
    fun getUuid(): UUID

    /**
     * Attaches a child node to this node.
     *
     * @param[child] The child to attach to this node.
     */
    fun addChild(child: Node)

    /**
     * Removes a given node from the set of children of this node.
     *
     * @param[child] The child node to remove.
     */
    fun removeChild(child: Node): Boolean

    /**
     * Removes a given node from the set of children of this node.
     * If possible, use [removeChild] instead.
     *
     * @param[name] The name of the child node to remove.
     */
    fun removeChild(name: String): Boolean

    /**
     * Returns all children with the given [name].
     */
    fun getChildrenByName(name: String): List<Node>

    /**
     * Returns the [Scene] this Node is ultimately attached to.
     * Will return null in case the Node is not attached to a [Scene] yet.
     */
    fun getScene(): Scene?

    /**
     *  Runs an operation recursively on the node itself and all child nodes.
     *
     *  @param[func] A lambda accepting a [Node], representing this node and its potential children.
     */
    fun runRecursive(func: (Node) -> Unit)

    /**
     * Generates an [OrientedBoundingBox] for this [Node]. This will take
     * geometry information into consideration if this Node implements [Geometry].
     * In case a bounding box cannot be determined, the function will return null.
     */
    fun generateBoundingBox(): OrientedBoundingBox? {
        val geometry = geometryOrNull()
        if(geometry == null) {
            logger.warn("$name: Assuming 3rd party BB generation")
            return boundingBox
        } else {
            boundingBox = geometry.generateBoundingBox(children)
            return boundingBox
        }
    }

    /**
     * Returns the maximum [OrientedBoundingBox] of this [Node] and all its children.
     */
    fun getMaximumBoundingBox(): OrientedBoundingBox

    /**
     *  Runs an operation recursively on the node itself and all child nodes.
     *
     *  @param[func] A Java [Consumer] accepting a [Node], representing this node and its potential children.
     */
    fun runRecursive(func: Consumer<Node>)

    /**
     * Returns the [ShaderProperty] given by [name], if it exists and is declared by
     * this class or a subclass inheriting from [Node]. Returns null if the [name] can
     * neither be found as a property, or as member of the shaderProperties HashMap the Node
     * might declare.
     */
    fun getShaderProperty(name: String): Any?

    companion object NodeHelpers {
        /**
         * Depth-first search for elements in a Scene.
         *
         * @param[origin] The [Node] to start the search at.
         * @param[func] A lambda taking a [Node] and returning a Boolean for matching.
         * @return A list of [Node]s that match [func].
         */
        @Suppress("unused")
        fun discover(origin: Node, func: (Node) -> Boolean): ArrayList<Node> {
            val visited = HashSet<Node>()
            val matched = ArrayList<Node>()

            fun discover(current: Node, f: (Node) -> Boolean) {
                if (!visited.add(current)) return
                for (v in current.children) {
                    if (f(v)) {
                        matched.add(v)
                    }
                    discover(v, f)
                }
            }

            discover(origin, func)

            return matched
        }
    }

    @Deprecated(message = "Moved to attribute material(), see AttributesExample for usage details", replaceWith = ReplaceWith("material()"))
    fun getMaterial(): Material {
        return this.materialOrNull()!!
    }

    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().dirty"))
    var dirty: Boolean
        get() = this.geometryOrNull()!!.dirty
        set(value) {
            this.ifGeometry {
                dirty = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().world"))
    var world: Matrix4f
        get() = this.spatialOrNull()!!.world
        set(value) {
            this.ifSpatial {
                world = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().iworld"))
    var iworld: Matrix4f
        get() = Matrix4f(spatialOrNull()!!.world).invert()
        set(ignored) {}

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().model"))
    var model: Matrix4f
        get() = this.spatialOrNull()!!.model
        set(value) {
            this.ifSpatial {
                model = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().imodel"))
    var imodel: Matrix4f
        get() = Matrix4f(spatialOrNull()!!.model).invert()
        set(ignored) {}

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().view"))
    var view: Matrix4f
        get() = this.spatialOrNull()!!.view
        set(value) {
            this.ifSpatial {
                view = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().iview"))
    var iview: Matrix4f
        get() = Matrix4f(spatialOrNull()!!.view).invert()
        set(ignored) {}

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().projection"))
    var projection: Matrix4f
        get() = this.spatialOrNull()!!.projection
        set(value) {
            this.ifSpatial {
                projection = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().iprojection"))
    var iprojection: Matrix4f
        get() = Matrix4f(spatialOrNull()!!.projection).invert()
        set(ignored) {}

//    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().modelView"))
//    var modelView: Matrix4f
//        get() = this.spatial().modelView
//        set(value) {
//            this.ifSpatial {
//                modelView = value
//            }
//        }
//
//    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().imodelView"))
//    var imodelView: Matrix4f
//        get() = this.spatial().imodelView
//        set(value) {
//            this.ifSpatial {
//                imodelView = value
//            }
//        }
//
//    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().mvp"))
//    var mvp: Matrix4f
//        get() = this.spatial().mvp
//        set(value) {
//            this.ifSpatial {
//                mvp = value
//            }
//        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().position"))
    var position: Vector3f
        get() = this.spatialOrNull()!!.position
        set(value) {
            this.ifSpatial {
                position = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().scale"))
    var scale: Vector3f
        get() = this.spatialOrNull()!!.scale
        set(value) {
            this.ifSpatial {
                scale = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().rotation"))
    var rotation: Quaternionf
        get() = this.spatialOrNull()!!.rotation
        set(value) {
            this.ifSpatial {
                rotation = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().wantsComposeModel"))
    var wantsComposeModel: Boolean
        get() = this.spatialOrNull()!!.wantsComposeModel
        set(value) {
            this.ifSpatial {
                wantsComposeModel = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().needsUpdate"))
    var needsUpdate: Boolean
        get() = this.spatialOrNull()!!.needsUpdate
        set(value) {
            this.ifSpatial {
                needsUpdate = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().needsUpdateWorld"))
    var needsUpdateWorld: Boolean
        get() = this.spatialOrNull()!!.needsUpdateWorld
        set(value) {
            this.ifSpatial {
                needsUpdateWorld = value
            }
        }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().updateWorld(recursive, force)"))
    fun updateWorld(recursive: Boolean, force: Boolean = false) {
        ifSpatial {
            updateWorld(recursive, force)
        }
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().composeModel()"))
    fun composeModel() {
        ifSpatial {
            composeModel()
        }
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().centerOn(position)"))
    fun centerOn(position: Vector3f): Vector3f {
        return this.spatialOrNull()!!.centerOn(position)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().putAbove(position)"))
    fun putAbove(position: Vector3f): Vector3f {
        return this.spatialOrNull()!!.putAbove(position)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().fitInto(sideLength, scaleUp)"))
    fun fitInto(sideLength: Float, scaleUp: Boolean = false): Vector3f {
        return this.spatialOrNull()!!.fitInto(sideLength, scaleUp)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().orientBetweenPoints(p1, p2, rescale, reposition)"))
    fun orientBetweenPoints(p1: Vector3f, p2: Vector3f, rescale: Boolean, reposition: Boolean): Quaternionf {
        return this.spatialOrNull()!!.orientBetweenPoints(p1, p2, rescale, reposition)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().orientBetweenPoints(p1, p2, rescale)"))
    fun orientBetweenPoints(p1: Vector3f, p2: Vector3f, rescale: Boolean): Quaternionf {
        return this.spatialOrNull()!!.orientBetweenPoints(p1, p2, rescale, false)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().orientBetweenPoints(p1, p2)"))
    fun orientBetweenPoints(p1: Vector3f, p2: Vector3f): Quaternionf {
        return this.spatialOrNull()!!.orientBetweenPoints(p1, p2, false, false)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().intersects(other)"))
    fun intersects(other: Node): Boolean {
        return this.spatialOrNull()!!.intersects(other)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().worldPosition(v)"))
    fun worldPosition(v: Vector3f? = null): Vector3f {
        return this.spatialOrNull()!!.worldPosition(v)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().intersectAABB(origin, dir)"))
    fun intersectAABB(origin: Vector3f, dir: Vector3f): MaybeIntersects {
        return this.spatialOrNull()!!.intersectAABB(origin, dir)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().localize(position)"))
    fun localize(position: FloatArray?) {
        return this.spatialOrNull()!!.localize(position)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().localize(position)"))
    fun localize(position: DoubleArray?) {
        return this.spatialOrNull()!!.localize(position)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().getFloatPosition(d)"))
    fun getFloatPosition(d: Int): Float {
        return this.spatialOrNull()!!.getFloatPosition(d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().bck(d)"))
    fun bck(d: Int) {
        return this.spatialOrNull()!!.bck(d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance, d)"))
    fun move(distance: Float, d: Int) {
        return this.spatialOrNull()!!.move(distance, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance, d)"))
    fun move(distance: Double, d: Int) {
        return this.spatialOrNull()!!.move(distance, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance)"))
    fun move(distance: RealLocalizable?) {
        return this.spatialOrNull()!!.move(distance)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance)"))
    fun move(distance: FloatArray?) {
        return this.spatialOrNull()!!.move(distance)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance)"))
    fun move(distance: DoubleArray?) {
        return this.spatialOrNull()!!.move(distance)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance, d)"))
    fun move(distance: Int, d: Int) {
        return this.spatialOrNull()!!.move(distance, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance, d)"))
    fun move(distance: Long, d: Int) {
        return this.spatialOrNull()!!.move(distance, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance)"))
    fun move(distance: Localizable?) {
        return this.spatialOrNull()!!.move(distance)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance)"))
    fun move(distance: IntArray?) {
        return this.spatialOrNull()!!.move(distance)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().move(distance)"))
    fun move(distance: LongArray?) {
        return this.spatialOrNull()!!.move(distance)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().numDimensions()"))
    fun numDimensions(): Int {
        return this.spatialOrNull()!!.numDimensions()
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().fwd(d)"))
    fun fwd(d: Int) {
        ifSpatial {
            fwd(d)
        }
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().getDoublePosition(d)"))
    fun getDoublePosition(d: Int): Double {
        return this.spatialOrNull()!!.getDoublePosition(d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos)"))
    fun setPosition(pos: RealLocalizable) {
        return this.spatialOrNull()!!.setPosition(pos)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos)"))
    fun setPosition(pos: FloatArray?) {
        return this.spatialOrNull()!!.setPosition(pos)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos)"))
    fun setPosition(pos: DoubleArray?) {
        return this.spatialOrNull()!!.setPosition(pos)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos, d)"))
    fun setPosition(pos: Float, d: Int) {
        return this.spatialOrNull()!!.setPosition(pos, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos, d)"))
    fun setPosition(pos: Double, d: Int) {
        return this.spatialOrNull()!!.setPosition(pos, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos)"))
    fun setPosition(pos: Localizable?) {
        return this.spatialOrNull()!!.setPosition(pos)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos)"))
    fun setPosition(pos: IntArray?) {
        return this.spatialOrNull()!!.setPosition(pos)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(pos)"))
    fun setPosition(pos: LongArray?) {
        return this.spatialOrNull()!!.setPosition(pos)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(position, d)"))
    fun setPosition(position: Int, d: Int) {
        return this.spatialOrNull()!!.setPosition(position, d)
    }

    @Deprecated(message = "Moved to attribute spatial(), see AttributesExample for usage details", replaceWith = ReplaceWith("spatial().setPosition(position, d)"))
    fun setPosition(position: Long, d: Int) {
        return this.spatialOrNull()!!.setPosition(position, d)
    }

    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().vertexSize"))
    var vertexSize: Int
        get() = this.geometryOrNull()!!.vertexSize
        set(value) {
            this.ifGeometry {
                vertexSize = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().texcoordSize"))
    var texcoordSize: Int
        get() = this.geometryOrNull()!!.texcoordSize
        set(value) {
            this.ifGeometry {
                texcoordSize = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().geometryType"))
    var geometryType: GeometryType
        get() = this.geometryOrNull()!!.geometryType
        set(value) {
            this.ifGeometry {
                geometryType = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().vertices"))
    var vertices: FloatBuffer
        get() = this.geometryOrNull()!!.vertices
        set(value) {
            this.ifGeometry {
                vertices = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().normals"))
    var normals: FloatBuffer
        get() = this.geometryOrNull()!!.normals
        set(value) {
            this.ifGeometry {
                normals = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().texcoords"))
    var texcoords: FloatBuffer
        get() = this.geometryOrNull()!!.texcoords
        set(value) {
            this.ifGeometry {
                texcoords = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().indices"))
    var indices: IntBuffer
        get() = this.geometryOrNull()!!.indices
        set(value) {
            this.ifGeometry {
                indices = value
            }
        }
    @Deprecated(message = "Moved to attribute geometry(), see AttributesExample for usage details", replaceWith = ReplaceWith("geometry().recalculateNormals()"))
    fun recalculateNormals() {
        ifGeometry {
            recalculateNormals()
        }
    }

}
