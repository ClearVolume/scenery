package graphics.scenery

import graphics.scenery.primitives.TextBoard
import graphics.scenery.attribute.renderable.DefaultRenderable
import graphics.scenery.attribute.renderable.Renderable
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.extensions.*
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.*

/**
 * Creating bounding boxes for [Node]s
 *
 * This class can render bounding boxes for any node, use it in the [REPL]
 * e.g. by:
 * ```
 * bb = new BoundingGrid();
 * bb.node = scene.find("ObjectName");
 * ```
 * Programmatic usage in the same way is possible of course ;)
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class BoundingGrid : Mesh("Bounding Grid") {
    protected var labels = HashMap<String, TextBoard>()

    /** Grid color for the bounding grid. */
    @ShaderProperty
    var gridColor: Vector3f = Vector3f(1.0f, 1.0f, 1.0f)

    /** Number of lines per grid axis. */
    @ShaderProperty
    var numLines: Int = 10

    /** Line width for the grid. */
    @ShaderProperty
    var lineWidth: Float = 1.2f

    /** Whether to show only the ticks on the grid, or show the full grid. */
    @ShaderProperty
    var ticksOnly: Int = 1

    /** Slack around transparent objects, 2% by default. */
    var slack = 0.02f

    /** The [Node] this bounding grid is attached to. Set to null to remove. */
    var node: Node? = null
        set(value) {
            updateNode(value, field)
            field = value
        }

    /** Stores the hash of the [node]'s bounding box to keep an eye on it. */
    protected var nodeBoundingBoxHash: Int = -1

    init {
        setMaterial(ShaderMaterial.fromFiles("DefaultForward.vert", "BoundingGrid.frag")) {
            blending.transparent = true
            blending.opacity = 0.8f
            blending.setOverlayBlending()
            cullingMode = Material.CullingMode.Front
        }

        labels = hashMapOf(
            "0" to TextBoard(),
            "x" to TextBoard(),
            "y" to TextBoard(),
            "z" to TextBoard()
        )

        labels.forEach { s, fontBoard ->
            fontBoard.text = s
            fontBoard.fontColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
            fontBoard.backgroundColor = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)
            fontBoard.transparent = 1
            fontBoard.spatial {
                scale = Vector3f(0.3f, 0.3f, 0.3f)
            }

            this.addChild(fontBoard)
        }
    }

    override fun createRenderable(): Renderable {
        return object : DefaultRenderable(this) {
            override fun preDraw(): Boolean {
                super.preDraw()
                if (node?.getMaximumBoundingBox().hashCode() != nodeBoundingBoxHash) {
                    logger.info(
                        "Updating bounding box (${
                            node?.getMaximumBoundingBox().hashCode()
                        } vs $nodeBoundingBoxHash"
                    )
                    updateNode(node, node)
                }

                return true
            }
        }
    }

    private fun updateNode(oldNode: Node?, newNode: Node?) {
        if(newNode != null) {
            oldNode?.removeChild(this)
            updateFromNode()
            newNode.addChild(this)
            newNode.spatialOrNull()?.updateWorld(true)
        }
    }

    protected fun updateFromNode() {
        node?.let { node ->
            val maxBoundingBox = node.getMaximumBoundingBox()
            nodeBoundingBoxHash = maxBoundingBox.hashCode()


            var min = maxBoundingBox.min
            var max = maxBoundingBox.max

            node.ifMaterial {
                logger.debug("Node ${node.name} is transparent: ${blending.transparent}")
                if(blending.transparent) {
                    min = min * (1.0f + slack)
                    max = max * (1.0f + slack)
                }
            }

            val b = Box(max - min)

            logger.debug("Bounding box of $node is $maxBoundingBox")

            val center = (max - min)*0.5f

            this.boundingBox = b.boundingBox

            val bGeometry = b.geometry()
            geometry {
                vertices = bGeometry.vertices
                normals = bGeometry.normals
                texcoords = bGeometry.texcoords
                indices = bGeometry.indices
            }
            spatial {
                position = maxBoundingBox.min + center
            }

            boundingBox?.let { bb ->
                // label coordinates are relative to the bounding box
                labels["0"]?.spatial()?.position = bb.min - Vector3f(0.1f, 0.0f, 0.0f)
                labels["x"]?.spatial()?.position = Vector3f(2.0f * bb.max.x() + 0.1f, 0.01f, 0.01f) - center
                labels["y"]?.spatial()?.position = Vector3f(-0.1f, 2.0f * bb.max.y(), 0.01f) - center
                labels["z"]?.spatial()?.position = Vector3f(-0.1f, 0.01f, 2.0f * bb.max.z()) - center

                spatial {
                    needsUpdate = true
                    needsUpdateWorld = true
                }
                geometry {
                    dirty = true
                }

                name = "Bounding Grid of ${node.name}"
            } ?: logger.error("Bounding box of $b is null")
        }
    }

    /**
     * Returns this bounding box' coordinates and associated [Node] as String.
     */
    override fun toString(): String {
        return "Bounding Box of ${node?.name}, coords: ${boundingBox?.min}/${boundingBox?.max}"
    }
}
