package graphics.scenery.volumes

import graphics.scenery.utils.Image
import graphics.scenery.utils.LazyLogger
import net.imagej.lut.LUTService
import net.imglib2.display.ColorTable
import org.joml.Vector3f
import org.joml.Vector4f
import org.scijava.plugin.Parameter
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Class for holding RGBA colormaps for volumes
 */
class Colormap(val buffer: ByteBuffer, val width: Int, val height: Int) {

    /**
     * Returns the value of the colormap, sampled at [position].
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun sample(position: Float): Vector4f {
        val bufferPosition: Float = position.coerceIn(0.0f, 1.0f) * width
        val previous = floor(bufferPosition).roundToInt()
        val next = ceil(bufferPosition).roundToInt()

        val globalOffset = width * 4 * height / 2
        val previousColor = globalOffset + previous * 4
        val nextColor = globalOffset + next * 4

        val b = buffer.duplicate()
        val color = ByteArray(8)
        @Suppress("USELESS_CAST")
        (b.position(previousColor) as? ByteBuffer)?.get(color, 0, 4)
        @Suppress("USELESS_CAST")
        (b.position(nextColor) as? ByteBuffer)?.get(color, 4, 4)
        val ub = color.toUByteArray()

        val c1 = Vector4f(ub[0].toFloat()/255.0f, ub[1].toFloat()/255.0f, ub[2].toFloat()/255.0f, ub[3].toFloat()/255.0f)
        val c2 = Vector4f(ub[4].toFloat()/255.0f, ub[5].toFloat()/255.0f, ub[6].toFloat()/255.0f, ub[7].toFloat()/255.0f)

        return c1.lerp(c2, bufferPosition - previous.toFloat())
    }

    companion object {
        val logger by LazyLogger()

        @Parameter
        protected var lutService: LUTService? = null

        /**
         * Creates a new color map from a [ByteBuffer], with dimensions given as [width] and [height].
         */
        @JvmStatic fun fromBuffer(buffer: ByteBuffer, width: Int, height: Int): Colormap {
            return Colormap(buffer.duplicate(), width, height)
        }

        /**
         * Creates a new colormap from an [array], with dimensions given as [width] and [height].
         */
        @JvmStatic fun fromArray(array: ByteArray, width: Int, height: Int): Colormap {
            return Colormap(ByteBuffer.wrap(array), width, height)
        }

        /**
         * Creates a new colormap from an [stream], with the file type/extension given in [extension].
         */
        @JvmStatic fun fromStream(stream: InputStream, extension: String): Colormap {
            val image = Image.fromStream(stream, extension)
            logger.info("Read image from $stream with ${image.contents.remaining()} bytes, size=${image.width}x${image.height}")
            return Colormap(image.contents, image.width, image.height)
        }

        /**
         * Creates a color map from an imglib2 [ColorTable].
         */
        @JvmStatic fun fromColorTable(colorTable: ColorTable): Colormap {
            val copies = 16
            val byteBuffer = ByteBuffer.allocateDirect(
                4 * colorTable.length * copies) // Num bytes * num components * color map length * height of color map texture
            val tmp = ByteArray(4 * colorTable.length)
            for (k in 0 until colorTable.length) {
                for (c in 0 until colorTable.componentCount) { // TODO this assumes numBits is 8, could be 16
                    tmp[4 * k + c] = colorTable[c, k].toByte()
                }
                if (colorTable.componentCount == 3) {
                    tmp[4 * k + 3] = 255.toByte()
                }
            }
            for (i in 0 until copies) {
                byteBuffer.put(tmp)
            }
            byteBuffer.flip()

            logger.info("Using ImageJ colormap $colorTable with size ${colorTable.length}x$copies")
            return fromBuffer(byteBuffer, colorTable.length, copies)
        }

        /**
         * Creates a color map from an imglib2 [ColorTable].
         */
        @JvmStatic fun fromColor(color: Vector4f): Colormap {
            val copies = 16
            val width = 256
            val byteBuffer = ByteBuffer.allocateDirect(
                4 * width * copies) // Num bytes * num components * color map length * height of color map texture
            val tmp = ByteArray(4 * width)
            for(i in 0 until width) {
                tmp[4 * i + 0] = (256-color[0]*i).toByte()
                tmp[4 * i + 1] = (256-color[1]*i).toByte()
                tmp[4 * i + 2] = (256-color[2]*i).toByte()
                tmp[4 * i + 3] = 255.toByte()
            }
            for (i in 0 until copies) {
                byteBuffer.put(tmp)
            }
            byteBuffer.flip()

            return fromBuffer(byteBuffer, width, copies)
        }

        /**
         * Tries to load a colormap from a file. Available colormaps can be queried with [list].
         */
        @JvmStatic fun get(name: String): Colormap {
            try {
                val luts = lutService?.findLUTs()
                val colorTable = luts?.let {
                    val url = it[name]
                    lutService?.loadLUT(url)
                } ?: throw IOException("Color map $name not found in ImageJ colormaps")

                return fromColorTable(colorTable)
            } catch(e: IOException) {
                logger.debug("LUT $name not found as ImageJ colormap, trying stream")
                logger.info("Using colormap $name from stream")
                val resource = Colormap::class.java.getResourceAsStream("colormap-$name.png")
                    ?: throw FileNotFoundException("Could not find color map for name $name (colormap-$name.png)")

                return fromStream(resource, "png")
            }
        }

        /**
         * Returns a list of strings containing the names of the available color maps for use with [get].
         */
        @JvmStatic fun list(): List<String> {
            // FIXME: Hardcoded for the moment, not nice.
            val list = arrayListOf("grays", "hot", "jet", "plasma", "viridis")
            lutService?.findLUTs()?.keys?.forEach { list.add(it) }

            return list
        }
    }
}
