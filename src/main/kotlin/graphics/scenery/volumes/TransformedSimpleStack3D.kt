package graphics.scenery.volumes

import graphics.scenery.Node
import net.imglib2.RandomAccessibleInterval
import net.imglib2.realtransform.AffineTransform3D
import org.joml.Matrix4f
import tpietzsch.multires.SimpleStack3D

/**
 * Class for wrapping [SimpleStack3D] [stack]s with custom transformations
 * governed by [node] and [actualSourceTransform].
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */

class TransformedSimpleStack3D<T>(val stack: SimpleStack3D<T>, val node: Node, val actualSourceTransform: AffineTransform3D) : SimpleStack3D<T> {
    override fun getType(): T {
        return stack.type as T
    }

    override fun getSourceTransform(): AffineTransform3D {
        val w = AffineTransform3D()
        val arr = FloatArray(16)
        Matrix4f(node.spatialOrNull()?.world).transpose().get(arr)

        w.set(*arr.map { it.toDouble() }.toDoubleArray())
        return w.concatenate(actualSourceTransform)
    }

    override fun getImage(): RandomAccessibleInterval<T> {
        return stack.image as RandomAccessibleInterval<T>
    }

    override fun equals(other: Any?): Boolean {
        return stack.hashCode() == other.hashCode()
    }

    override fun hashCode(): Int {
        return stack.hashCode()
    }
}
