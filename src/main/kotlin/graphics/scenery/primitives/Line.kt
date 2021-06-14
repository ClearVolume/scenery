package graphics.scenery.primitives

import graphics.scenery.*
import org.joml.Vector3f
import graphics.scenery.backends.ShaderType
import graphics.scenery.geometry.GeometryType
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.*
import graphics.scenery.attribute.geometry.HasGeometry
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.spatial.HasSpatial
import org.joml.Vector4f

/**
 * Class for creating 3D lines, derived from [Node] and using [HasGeometry]
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class Line @JvmOverloads constructor(var capacity: Int = 50, transparent: Boolean = false, val simple: Boolean = false) : DefaultNode("Line"),
    HasRenderable, HasMaterial, HasSpatial, HasGeometry {

    /** Whether the line should be rendered as transparent or not. */
    var transparent: Boolean = transparent
        set(value) {
            field = value
            activateTransparency(value)
        }

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

    init {
        addMaterial {
            cullingMode = Material.CullingMode.None
        }

        addGeometry {
            if(simple) {
                geometryType = GeometryType.LINE
            } else {
                geometryType = GeometryType.LINE_STRIP_ADJACENCY
                activateTransparency(transparent)
            }

            vertices.limit(0)
            normals.limit(0)
            texcoords.limit(0)
        }

        addRenderable()

        addSpatial()
    }

    protected fun activateTransparency(transparent: Boolean) {
        if(simple) {
            return
        }

        val newMaterial: Material
        if(transparent) {
            newMaterial = ShaderMaterial.fromFiles(
                "${this::class.java.simpleName}.vert",
                "${this::class.java.simpleName}.geom",
                "${this::class.java.simpleName}Forward.frag"
            )

            newMaterial.blending.opacity = 1.0f
            newMaterial.blending.setOverlayBlending()
        } else {
            newMaterial = ShaderMaterial.fromClass(
                this::class.java,
                listOf(ShaderType.VertexShader, ShaderType.GeometryShader, ShaderType.FragmentShader)
            )
        }
        material {
            newMaterial.diffuse = diffuse
            newMaterial.specular = specular
            newMaterial.ambient = ambient
            newMaterial.metallic = metallic
            newMaterial.roughness = roughness
        }
        setMaterial(newMaterial) {
            blending.transparent = transparent
            cullingMode = Material.CullingMode.None
        }
    }

    /**
     * Adds points to the line.
     * If the line's vertex buffer cannot store all o the points,
     * a copy of it will be created that can store the additional points, plus
     * double it's initial capacity.
     *
     * @param points     The vector containing the points
     */
    fun addPoints(vararg points: Vector3f) {
        geometry {
            if(vertices.limit() + 3 * points.size >= vertices.capacity()) {
                val newVertices = BufferUtils.allocateFloat(vertices.capacity() + points.size * 3 + 3 * capacity)
                vertices.position(0)
                vertices.limit(vertices.capacity())
                newVertices.put(vertices)
                newVertices.limit(vertices.limit())

                vertices = newVertices

                val newNormals = BufferUtils.allocateFloat(vertices.capacity() + points.size * 3 + 3 * capacity)
                normals.position(0)
                normals.limit(normals.capacity())
                newNormals.put(normals)
                newNormals.limit(normals.limit())

                normals = newNormals


                val newTexcoords = BufferUtils.allocateFloat(vertices.capacity() + points.size * 2 + 2 * capacity)
                texcoords.position(0)
                texcoords.limit(texcoords.capacity())
                newTexcoords.put(texcoords)
                newTexcoords.limit(texcoords.limit())

                texcoords = newTexcoords

                capacity = vertices.capacity()/3
            }

            vertices.position(vertices.limit())
            vertices.limit(vertices.limit() + points.size * 3)
            points.forEach { v -> v.get(vertices) }
            vertices.position(vertices.limit())
            vertices.flip()

            normals.position(normals.limit())
            normals.limit(normals.limit() + points.size * 3)
            points.forEach { v -> v.get(normals) }
            normals.position(normals.limit())
            normals.flip()

            texcoords.position(texcoords.limit())
            texcoords.limit(texcoords.limit() + points.size * 2)
            points.forEach { _ ->
                texcoords.put(0.0f)
                texcoords.put(0.0f)
            }
            texcoords.position(texcoords.limit())
            texcoords.flip()

            dirty = true
            vertexCount = vertices.limit()/vertexSize
        }

        boundingBox = generateBoundingBox()
    }

    /**
     * Add a point to the line.
     *
     * @param points     The vector containing the position of the point.
     */
    fun addPoint(point: Vector3f) {
        addPoints(point)
    }

    /**
     * Convenience function to add a list of vectors to the line.
     *
     * @param points A list of Vector3fs
     */
    fun addPoints(points: List<Vector3f>) {
        addPoints(*points.toTypedArray())
    }

    /**
     * Fully clears the line.
     */
    fun clearPoints() {
        geometry {
            vertices.clear()
            normals.clear()
            texcoords.clear()

            vertices.limit(0)
            normals.limit(0)
            texcoords.limit(0)
        }
    }

    companion object {
        /**
         * Creates a set of lines with a specified number of points per line. The distance between points
         * can be set by [step] and the overall scale can be defined by [scale].
         * Note, the lines are generated with an additional random offset between them (to make the data
         * a bit less uniform).
         * @param numLines Number of lines to be generated
         * @param numPositions Number of positions to be generated per line
         * @param step The distance between two positions (i.e. to make a line longer)
         * @param scale The overall scale of the whole line set
         * @return An array of Line objects
         */
        @JvmStatic fun createLines(numLines: Int, numPositions: Int, step: Float, scale: Float): List<Line> {
            return (0 until numLines).map { i ->
                val line = Line()
                val randOffset = Random.randomFromRange(0.0f, 2.0f) - (numLines / 2).toFloat()
                for (j in -numPositions / 2 until numPositions / 2) {
                    line.addPoint(Vector3f(scale * (randOffset + i.toFloat()), scale * step * j.toFloat(), -100.0f))
                }
                line
            }
        }
    }
}
