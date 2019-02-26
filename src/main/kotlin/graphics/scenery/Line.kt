package graphics.scenery

import cleargl.GLVector
import graphics.scenery.backends.ShaderType
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Class for creating 3D lines, derived from [Node] and using [HasGeometry]
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class Line(var capacity: Int = 50) : Node("Line"), HasGeometry {
    /** Size of one vertex (e.g. 3 in 3D) */
    override val vertexSize: Int = 3
    /** Size of one texcoord (e.g. 2 in 3D) */
    override val texcoordSize: Int = 2
    /** Geometry type -- Default for Line is [GeometryType.LINE] */
    override var geometryType: GeometryType = GeometryType.LINE_STRIP_ADJACENCY
    /** Vertex buffer */
    override var vertices: FloatBuffer = BufferUtils.allocateFloat(3 * capacity)
    /** Normal buffer */
    override var normals: FloatBuffer = BufferUtils.allocateFloat(3 * capacity)
    /** Texcoord buffer */
    override var texcoords: FloatBuffer = BufferUtils.allocateFloat(2 * capacity)
    /** Index buffer */
    override var indices: IntBuffer = IntBuffer.wrap(intArrayOf())

    /** Shader property for the line's starting segment color. Consumed by the renderer. */
    @ShaderProperty
    var startColor = GLVector(0.0f, 1.0f, 0.0f, 1.0f)

    /** Shader property for the line's color. Consumed by the renderer. */
    @ShaderProperty
    var lineColor = GLVector(1.0f, 1.0f, 1.0f, 1.0f)

    /** Shader property for the line's end segment color. Consumed by the renderer. */
    @ShaderProperty
    var endColor = GLVector(0.7f, 0.5f, 0.5f, 1.0f)

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

    init {
        material = ShaderMaterial.fromClass(this::class.java, listOf(ShaderType.VertexShader, ShaderType.GeometryShader, ShaderType.FragmentShader))
        vertices.limit(0)
        normals.limit(0)
        texcoords.limit(0)

        material.cullingMode = Material.CullingMode.None
    }

    /**
     * Adds a line point to the line.
     *
     * @param p     The vector containing the vertex data
     */
    fun addPoint(p: GLVector) {
        if (vertices.limit() + 3 > vertices.capacity()) {
            val newVertices = BufferUtils.allocateFloat(vertices.capacity() + 3 * capacity)
            vertices.position(0)
            vertices.limit(vertices.capacity())
            newVertices.put(vertices)
            newVertices.limit(vertices.limit())

            vertices = newVertices

            val newNormals = BufferUtils.allocateFloat(vertices.capacity() + 3 * capacity)
            normals.position(0)
            normals.limit(normals.capacity())
            newNormals.put(normals)
            newNormals.limit(normals.limit())

            normals = newNormals


            val newTexcoords = BufferUtils.allocateFloat(vertices.capacity() + 2 * capacity)
            texcoords.position(0)
            texcoords.limit(texcoords.capacity())
            newTexcoords.put(texcoords)
            newTexcoords.limit(texcoords.limit())

            texcoords = newTexcoords

            capacity = vertices.capacity() / 3
        }

        vertices.position(vertices.limit())
        vertices.limit(vertices.limit() + 3)
        vertices.put(p.toFloatArray())
        vertices.flip()

        normals.position(normals.limit())
        normals.limit(normals.limit() + 3)
        normals.put(p.toFloatArray())
        normals.flip()

        texcoords.position(texcoords.limit())
        texcoords.limit(texcoords.limit() + 2)
        texcoords.put(0.225f)
        texcoords.put(0.225f)
        texcoords.flip()

        dirty = true
        vertexCount = vertices.limit() / vertexSize

        boundingBox = generateBoundingBox()
    }

    /**
     * Fully clears the line.
     */
    fun clearPoints() {
        vertices.clear()
        normals.clear()
        texcoords.clear()

        vertices.limit(0)
        normals.limit(0)
        texcoords.limit(0)
    }
}
