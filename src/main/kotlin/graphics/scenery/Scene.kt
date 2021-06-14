package graphics.scenery

import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.spatial.HasSpatial
import org.joml.Vector3f
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream
import kotlin.collections.ArrayList
import kotlin.streams.asSequence

/**
 * Scene class. A Scene is a special kind of [Node] that can only exist once per graph,
 * as a root node.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class Scene : DefaultNode("RootNode"), HasRenderable, HasMaterial, HasSpatial {

    /** Temporary storage of the active observer ([Camera]) of the Scene. */
    var activeObserver: Camera? = null

    internal var sceneSize: AtomicLong = AtomicLong(0L)

    /** Callbacks to be called when a child is added to the scene */
    var onChildrenAdded = ConcurrentHashMap<String, (Node, Node) -> Unit>()
    /** Callbacks to be called when a child is removed from the scene */
    var onChildrenRemoved = ConcurrentHashMap<String, (Node, Node) -> Unit>()
    /** Callbacks to be called when a child is removed from the scene */
    var onNodePropertiesChanged = ConcurrentHashMap<String, (Node) -> Unit>()

    init {
        addRenderable()
        addMaterial()
        addSpatial()
    }

    /**
     * Adds a [Node] to the Scene, at the position given by [parent]
     *
     * @param[n] The node to add.
     * @param[parent] The node to attach [n] to.
     */
    @Suppress("unused")
    fun addNode(n: Node, parent: Node) {
        if (n.name == "RootNode") {
            throw IllegalStateException("Only one RootNode may exist per scenegraph. Please choose a different name.")
        }

        discover(this, { node -> node == parent }).first().addChild(n)
    }

    /**
     * Find the currently active observer in the Scene.
     *
     * TODO: Store once-found camera in [activeObserver]
     *
     * @return The [Camera] that is currently active.
     */
    fun findObserver(): Camera? {
        return if(activeObserver == null) {
            val observers = discover(this, { n -> n is Camera}, useDiscoveryBarriers = true)

            activeObserver = observers.firstOrNull() as Camera?
            activeObserver
        } else {
            activeObserver
        }
    }

    /**
     * Depth-first search for elements in a Scene.
     *
     * @param[s] The Scene to search in
     * @param[func] A lambda taking a [Node] and returning a Boolean for matching.
     * @return A list of [Node]s that match [func].
     */
    fun discover(s: Scene, func: (Node) -> Boolean, useDiscoveryBarriers: Boolean = false): ArrayList<Node> {
        val visited = HashSet<Node>()
        val matched = ArrayList<Node>()

        fun discover(current: Node, f: (Node) -> Boolean) {
            if (!visited.add(current)) return
            for (v in current.children) {
                if (f(v)) {
                    matched.add(v)
                }

                if(!(useDiscoveryBarriers && v.discoveryBarrier)) {
                    discover(v, f)
                }
            }
        }

        discover(s, func)

        return matched
    }

    /**
     * Discovers [Node]s in a Scene [s] via [func] in a parallel manner, optionally stopping at discovery barriers,
     * if [useDiscoveryBarriers] is true.
     */
    @Suppress("UNUSED_VARIABLE", "unused")
    fun discoverParallel(s: Scene, func: (Node) -> Boolean, useDiscoveryBarriers: Boolean = false) = runBlocking<List<Node>> {
        val visited = Collections.synchronizedSet(HashSet<Node>(sceneSize.toInt()))
        val crs = Collections.synchronizedSet(HashSet<Job>())
        val matches = Collections.synchronizedSet(HashSet<Node>(sceneSize.toInt()))

        val channel = Channel<Node>()

        fun discover(current: Node, f: (Node) -> Boolean) {
            if (!visited.add(current)) return

            crs.add(launch {
                for(v in current.children) {
                    if (f(v)) {
//                        channel.send(v)
                        matches.add(v)
                    }

                    if (useDiscoveryBarriers && v.discoveryBarrier) {
                        logger.trace("Hit discovery barrier, not recursing deeper.")
                    } else {
                        discover(v, f)
                    }
                }
            })
        }

        discover(s as Node, func)

//        channel.close()

        crs.forEach { it.join() }
//        channel.consumeEach { logger.info("Added ${it.name}"); matches.add(it) }
        matches.toList()
    }

    /**
     * Depth-first search routine for a Scene.
     */
    @Suppress("unused")
    fun dfs(s: Scene) {
        val visited = HashSet<Node>()
        fun dfs(current: Node) {
            if (!visited.add(current)) return
            for (v in current.children)
                dfs(v)
        }

        dfs(s.children[0])
    }

    /**
     * Find a [Node] by its name.
     *
     * @param[name] The name of the [Node] to find.
     * @return The first [Node] matching [name].
     */
    @Suppress("unused")
    fun find(name: String): Node? {
        val matches = discover(this, { n -> n.name == name })

        return matches.firstOrNull()
    }

    /**
     * Find a [Node] by its class name.
     *
     * @param[className] The class name of the [Node] to find.
     * @return A list of Nodes with class name [name].
     */
    @Suppress("unused")
    fun findByClassname(className: String): List<Node> {
        return this.discover(this, { n -> n.javaClass.simpleName.contains(className) })
    }

    /**
     * Data class for selection matches, contains the [Node] as well as the distance
     * from the observer to it.
     */
    data class RaycastMatch(val node: Node, val distance: Float)

    /**
     * Data class for raycast results, including all matches, and the ray's origin and direction.
     */
    data class RaycastResult(val matches: List<RaycastMatch>, val initialPosition: Vector3f, val initialDirection: Vector3f)

    /**
     * Performs a raycast to discover objects in this [Scene] that would be intersected
     * by a ray originating from [position], shot in [direction]. This method can
     * be given a list of classes as [ignoredObjects], which will then be ignored for
     * the raycast. If [debug] is true, a set of spheres is placed along the cast ray.
     */
    @JvmOverloads fun raycast(position: Vector3f, direction: Vector3f,
                              ignoredObjects: List<Class<*>>,
                              debug: Boolean = false): RaycastResult {
        if (debug) {
            val indicatorMaterial = DefaultMaterial()
            indicatorMaterial.diffuse = Vector3f(1.0f, 0.2f, 0.2f)
            indicatorMaterial.specular = Vector3f(1.0f, 0.2f, 0.2f)
            indicatorMaterial.ambient = Vector3f(0.0f, 0.0f, 0.0f)

            for(it in 5..50) {
                val s = Box(Vector3f(0.08f, 0.08f, 0.08f))
                s.setMaterial(indicatorMaterial)
                s.spatial {
                    this.position = position + direction * it.toFloat()
                }
                this.addChild(s)
            }
        }

        val matches = this.discover(this, { node ->
            node.visible && !ignoredObjects.any{it.isAssignableFrom(node.javaClass)}
        }).flatMap { (
            if (it is InstancedNode)
                Stream.concat(Stream.of(it as Node), it.instances.map { instanceNode -> instanceNode as Node }.stream())
            else
                Stream.of(it)).asSequence()
        }.map {
            Pair(it, it.spatialOrNull()?.intersectAABB(position, direction))
        }.filter {
            it.first !is InstancedNode
        }.filter {
            it.second is MaybeIntersects.Intersection && (it.second as MaybeIntersects.Intersection).distance > 0.0f
        }.map {
            RaycastMatch(it.first, (it.second as MaybeIntersects.Intersection).distance)
        }.sortedBy {
            it.distance
        }

        if (debug) {
            logger.info(matches.joinToString(", ") { "${it.node.name} at distance ${it.distance}" })

            val m = DefaultMaterial()
            m.diffuse = Vector3f(1.0f, 0.0f, 0.0f)
            m.specular = Vector3f(0.0f, 0.0f, 0.0f)
            m.ambient = Vector3f(0.0f, 0.0f, 0.0f)

            matches.firstOrNull()?.let {
                it.node.setMaterial(m)
            }
        }

        return RaycastResult(matches, position, direction)
    }
}
