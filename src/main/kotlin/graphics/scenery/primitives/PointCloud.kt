package graphics.scenery.primitives

import graphics.scenery.BufferUtils
import graphics.scenery.Mesh
import graphics.scenery.OrientedBoundingBox
import graphics.scenery.ShaderMaterial
import graphics.scenery.geometry.GeometryType
import org.joml.Vector3f
import java.nio.FloatBuffer
import java.nio.file.FileSystems
import java.nio.file.Files

/**
 * Constructs a point cloud, with a given base [pointRadius].
 *
 * @author Kyle Harrington <kharrington@uidaho.edu>
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class PointCloud(var pointRadius: Float = 1.0f, override var name: String = "PointCloud") : Mesh(name) {

    init {
        geometry {
            /** [PointCloud]s are rendered as point geometry. */
            geometryType = GeometryType.POINTS
        }
        renderable {
            // PointClouds do not get billboarded.
            isBillboard = false
            // we are going to use shader files whose name is derived from the class name.
            // -> PointCloud.vert, PointCloud.frag
        }
        setMaterial(ShaderMaterial.fromClass(this::class.java)) {
            blending.transparent = true
        }
    }

    /**
     * Sets up normal and texcoord buffers from the vertex buffers.
     */
    fun setupPointCloud() {
        geometry {
            if( this.texcoords.limit() == 0 ) {// Only preinitialize if texcoords has not been preinialized
                this.texcoords = BufferUtils.allocateFloat(vertices.limit() / 3 * 2)
                var i = 0
                while (i < this.texcoords.limit() - 1) {
                    this.texcoords.put(i, pointRadius)
                    this.texcoords.put(i + 1, pointRadius)
                    i += 2
                }
            }
            if( this.normals.limit() == 0 ) {// Only preinitialize if need be
                this.normals = BufferUtils.allocateFloatAndPut(FloatArray(vertices.limit(), { 1.0f }))
            }
        }
    }

    /**
     * Reads the [PointCloud] from [filename], assuming the ThunderSTORM format.
     * See http://www.neurocytolab.org/tscolumns/ for format documentation.
     *
     * This function automatically determines the used separator char, and supports
     * comma (,), semicolon (;), and tab as separators.
     */
    fun readFromPALM(filename: String) {
        geometry {
            val count = Files.lines(FileSystems.getDefault().getPath(filename)).count()
            this.vertices = BufferUtils.allocateFloat(count.toInt() * 3)
            this.normals = BufferUtils.allocateFloat(count.toInt() * 3)
            this.texcoords = BufferUtils.allocateFloat(count.toInt() * 2)

            logger.info("Reading ${count/3} locations from $filename...")

            val boundingBoxCoords = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            var separator = ""

            Files.lines(FileSystems.getDefault().getPath(filename)).forEach {
                // try to figure out separator char
                if(separator == "") {
                    separator = when {
                        it.split(",").size >= 6 -> ","
                        it.split("\t").size >= 6 -> "\t"
                        it.split(";").size >= 6 -> ";"
                        else -> throw IllegalStateException("Could not determine separator char for file $filename.")
                    }

                    logger.debug("Determined <$separator> as separator char for $filename.")
                }

                val arr = it.split(separator)
                if (!arr[0].startsWith("\"")) {
                    this.vertices.put(arr[1].toFloat())
                    this.vertices.put(arr[2].toFloat())
                    this.vertices.put(arr[3].toFloat())

                    boundingBoxCoords[0] = minOf(arr[1].toFloat(), boundingBoxCoords[0])
                    boundingBoxCoords[2] = minOf(arr[2].toFloat(), boundingBoxCoords[2])
                    boundingBoxCoords[4] = minOf(arr[3].toFloat(), boundingBoxCoords[4])

                    boundingBoxCoords[1] = maxOf(arr[1].toFloat(), boundingBoxCoords[1])
                    boundingBoxCoords[3] = maxOf(arr[2].toFloat(), boundingBoxCoords[3])
                    boundingBoxCoords[5] = maxOf(arr[3].toFloat(), boundingBoxCoords[5])

                    this.normals.put(arr[1].toFloat())
                    this.normals.put(arr[2].toFloat())
                    this.normals.put(arr[3].toFloat())

                    this.texcoords.put(arr[4].toFloat())
                    this.texcoords.put(arr[5].toFloat())
                }
            }

            logger.info("Finished reading. Found ${vertices.capacity()/3} locations.")

            this.vertices.flip()
            this.normals.flip()
            this.texcoords.flip()

            boundingBox = OrientedBoundingBox(this@PointCloud, boundingBoxCoords)
        }

    }

    /**
     * Sets the [color] for all vertices.
     */
    fun setColor(color: Vector3f) {
        val colorBuffer = FloatBuffer.allocate(geometry().vertices.capacity())
        while(colorBuffer.hasRemaining()) {
            color.get(colorBuffer)
        }
    }

    companion object {
        /**
         * Creates a point cloud from an [array] of floats.
         */
        fun fromArray(array: FloatArray): PointCloud {
            val p = PointCloud()
            p.geometry().vertices = FloatBuffer.wrap(array)
            p.setupPointCloud()

            return p
        }

        /**
         * Creates a point cloud from a [buffer] of floats.
         */
        fun fromBuffer(buffer: FloatBuffer): PointCloud {
            val p = PointCloud()
            p.geometry().vertices = buffer.duplicate()
            p.setupPointCloud()

            return p
        }
    }
}
