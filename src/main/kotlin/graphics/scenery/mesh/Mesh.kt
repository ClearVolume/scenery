package graphics.scenery.mesh

import org.joml.Vector3f
import gnu.trove.map.hash.THashMap
import gnu.trove.set.hash.TLinkedHashSet
import graphics.scenery.*
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.times
import org.lwjgl.system.MemoryUtil
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.file.Files
import java.util.ArrayList
import java.util.HashMap

/**
 * Simple Mesh class to store geometry, inherits from [HasGeometry].
 * Can also be used for grouping objects easily.
 *
 * Also see [HasGeomerty]  for more interface details.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class Mesh(override var name: String = "Mesh") : Node(name), HasGeometry {
    /** Vertex storage array. Also see [HasGeometry] */
    @Transient final override var vertices: FloatBuffer = BufferUtils.allocateFloat(0)
    /** Normal storage array. Also see [HasGeometry] */
    @Transient final override var normals: FloatBuffer = BufferUtils.allocateFloat(0)
    /** Texcoord storage array. Also see [HasGeometry] */
    @Transient final override var texcoords: FloatBuffer = BufferUtils.allocateFloat(0)
    /** Index storage array. Also see [HasGeometry] */
    @Transient final override var indices: IntBuffer = BufferUtils.allocateInt(0)

    /** Vertex element size. Also see [HasGeometry] */
    final override var vertexSize = 3
    /** Texcoord element size. Also see [HasGeometry] */
    final override var texcoordSize = 2
    /** Geometry type of the Mesh. Also see [HasGeometry] and [GeometryType] */
    final override var geometryType = GeometryType.TRIANGLES
}
