package graphics.scenery

import cleargl.GLVector
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
    var gridColor: GLVector = GLVector(1.0f, 1.0f, 1.0f)

    /** Number of lines per grid axis. */
    @ShaderProperty
    var numLines: Int = 10

    /** Line width for the grid. */
    @ShaderProperty
    var lineWidth: Float = 1.2f

    /** Whether to show only the ticks on the grid, or show the full grid. */
    @ShaderProperty
    var ticksOnly: Int = 1

    /** The [Node] this bounding grid is attached to. Set to null to remove. */
    var node: Node? = null
        set(value) {
            if (value == null) {
                field?.removeChild(this)
                field?.updateWorld(true)

                field = value
            } else {
                field = value
                node?.removeChild(this)
                updateFromNode()
                value.addChild(this)

                value.updateWorld(true)
            }
        }

    /** Stores the hash of the [node]'s bounding box to keep an eye on it. */
    protected var nodeBoundingBoxHash: Int = -1

    init {
        material = ShaderMaterial.fromFiles("DefaultForward.vert", "BoundingGrid.frag")
        material.blending.transparent = true
        material.blending.opacity = 0.8f
        material.blending.setOverlayBlending()
        material.cullingMode = Material.CullingMode.Front


        labels = hashMapOf(
            "0" to TextBoard(),
            "x" to TextBoard(),
            "y" to TextBoard(),
            "z" to TextBoard()
        )

        labels.forEach { s, fontBoard ->
            fontBoard.text = s
            fontBoard.fontColor = GLVector(1.0f, 1.0f, 1.0f)
            fontBoard.backgroundColor = GLVector(0.0f, 0.0f, 0.0f)
            fontBoard.transparent = 1
            fontBoard.scale = GLVector(0.3f, 0.3f, 0.3f)

            this.addChild(fontBoard)
        }
    }

    override fun preDraw() {
        super.preDraw()

        if (node?.getMaximumBoundingBox()?.hashCode() != nodeBoundingBoxHash) {
            logger.debug("Updating bounding box (${node?.getMaximumBoundingBox()?.hashCode()} vs $nodeBoundingBoxHash")
            node = node
        }
    }

    protected fun updateFromNode() {
        node?.let { node ->
            val maxBoundingBox = node.getMaximumBoundingBox()
            nodeBoundingBoxHash = maxBoundingBox.hashCode()

            val min = maxBoundingBox.min
            val max = maxBoundingBox.max

            val b = Box(max - min)

            logger.debug("Bounding box of $node is $maxBoundingBox")

            val center = (max - min) * 0.5f

            this.vertices = b.vertices
            this.normals = b.normals
            this.texcoords = b.texcoords
            this.indices = b.indices

            this.boundingBox = b.boundingBox
            this.position = maxBoundingBox.min + center

            boundingBox?.let { bb ->
                // label coordinates are relative to the bounding box
                labels["0"]?.position = bb.min - GLVector(0.1f, 0.0f, 0.0f)
                labels["x"]?.position = GLVector(2.0f * bb.max.x() + 0.1f, 0.01f, 0.01f) - center
                labels["y"]?.position = GLVector(-0.1f, 2.0f * bb.max.y(), 0.01f) - center
                labels["z"]?.position = GLVector(-0.1f, 0.01f, 2.0f * bb.max.z()) - center

                this.needsUpdate = true
                this.needsUpdateWorld = true

                this.dirty = true

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
