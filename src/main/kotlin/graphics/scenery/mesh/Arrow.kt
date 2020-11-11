package graphics.scenery.mesh

import graphics.scenery.*
import graphics.scenery.backends.ShaderType
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.joml.Vector3f
import org.joml.Vector4f
import java.nio.IntBuffer

/**
 * Class for creating 3D arrows, derived from [Node] and using [HasGeometry].
 * The arrow is defined with [vector] having the vector's foot/start as the
 * reference coordinate (arrow's foot will appear at the coordinate given to setPosition()).
 *
 *   /|\
 *  / | \
 * /--+--\
 *    |
 *    |
 *    |
 * The arrow is created as the main segment (drawn vertically bottom to up),
 * then 3 segments to build a triangle and then another triangle perpendicular
 * to the former one; altogehter 7 segments drawn sequentially.
 * The arrow head scales with the length of the arrow.
 *
 * @author Vladimir Ulman <ulman@mpi-cbg.de>
 */
class Arrow(var vector: Vector3f = Vector3f(0.0f)) : Mesh("Arrow") {
    /** Shader property for the line's starting segment color. Consumed by the renderer. */
    @ShaderProperty
    var startColor = Vector4f(0.0f, 1.0f, 0.0f, 1.0f)

    /** Shader property for the line's color. Consumed by the renderer. */
    @ShaderProperty
    var lineColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f)

    /** Shader property for the line's end segment color. Consumed by the renderer. */
    @ShaderProperty
    var endColor = Vector4f(0.7f, 0.5f, 0.5f, 1.0f)

    /** Shader property for the line's cap length (start and end caps). Consumed by the renderer. */
    @ShaderProperty
    var capLength = 1

    /** Shader property to keep track of the current number of vertices. Consumed by the renderer. */
    @ShaderProperty
    var vertexCount: Int = 0
        private set

    /** Shader property for the line's edge width. Consumed by the renderer. */
    @ShaderProperty
    var edgeWidth = 2.0f

    //shortcut to null vector... to prevent from creating it anew with every call to reshape()
    private val zeroGLvec = Vector3f(0.0f, 0.0f, 0.0f)

    init {
        /** Geometry type -- Default for Line is [GeometryType.LINE] */
        geometryType = GeometryType.LINE_STRIP_ADJACENCY
        /** Vertex buffer */
        vertices = BufferUtils.allocateFloat(30)
        /** Normal buffer */
        normals = BufferUtils.allocateFloat(30)
        /** Texcoord buffer */
        texcoords = BufferUtils.allocateFloat(20)
        /** Index buffer */
        indices = IntBuffer.wrap(intArrayOf())

        val shaderTypes = listOf(ShaderType.VertexShader, ShaderType.GeometryShader, ShaderType.FragmentShader)
        material = ShaderMaterial.fromClass(Line::class.java, shaderTypes)
        material.cullingMode = Material.CullingMode.None

        reshape(vector)
    }

    /**
     * Changes the shape of this arrow.
     *
     * @param vector The vector defining the shape of the arrow
     */
    fun reshape(vector: Vector3f) {
        //init the data structures
        clearPoints()

        /** create the vector shape */
        //first of the two mandatory surrounding fake points that are never displayed
        addPoint(zeroGLvec)

        //the main "vertical" segment of the vector
        addPoint(zeroGLvec)
        addPoint(vector)

        //the "horizontal" base segment of the "arrow head" triangles
        var base = if (vector.x() == 0.0f && vector.y() == 0.0f) {
            //the input 'vector' must be parallel to the z-axis,
            //we can use this particular 'base' then
            Vector3f(0.0f, 1.0f, 0.0f)
        }
        else {
            //vector 'base' is perpendicular to the input 'vector'
            Vector3f(-vector.y(), vector.x(), 0.0f).normalize()
        }

        //the width of the "arrow head" triangle
        val v = 0.1f * vector.length()

        //the first triangle:
        base = base * v
        addPoint(vector.times(0.8f).plus(base))
        addPoint(vector.times(0.8f).minus(base))
        addPoint(vector)
        //NB: the 0.8f defines the height (1-0.8) of the "arrow head" triangle

        //the second triangle:
        base = base.cross(vector).normalize().times(v)
        addPoint(vector.times(0.8f).plus(base))
        addPoint(vector.times(0.8f).minus(base))
        addPoint(vector)

        //second of the two mandatory surrounding fake points that are never displayed
        addPoint(vector)
    }

    private fun addPoint(p: Vector3f) {
        vertices.position(vertices.limit())
        vertices.limit(vertices.limit() + 3)
        p.get(vertices)
        vertices.position(vertices.position() + 3)
        vertices.flip()

        normals.position(normals.limit())
        normals.limit(normals.limit() + 3)
        p.get(normals)
        normals.position(normals.position() + 3)
        normals.flip()

        texcoords.position(texcoords.limit())
        texcoords.limit(texcoords.limit() + 2)
        texcoords.put(0.225f)
        texcoords.put(0.225f)
        texcoords.flip()

        dirty = true
        vertexCount = vertices.limit()/vertexSize

        boundingBox = generateBoundingBox()
    }

    private fun clearPoints() {
        vertices.clear()
        normals.clear()
        texcoords.clear()

        vertices.limit(0)
        normals.limit(0)
        texcoords.limit(0)
    }
}
