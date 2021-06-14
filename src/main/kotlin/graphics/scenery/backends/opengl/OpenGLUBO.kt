package graphics.scenery.backends.opengl

import graphics.scenery.InstancedNode
import graphics.scenery.Node
import graphics.scenery.backends.UBO
import java.nio.ByteBuffer

/**
 * UBO class for OpenGL
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class OpenGLUBO(val backingBuffer: OpenGLRenderer.OpenGLBuffer? = null) : UBO() {
    var offset = 0
    var binding = 0

    /**
     * Populates the [backingBuffer] with the members of this UBO, subject to the determined
     * sizes and alignments. A buffer [offset] can be given. This routine checks via it's super
     * if an actual buffer update is required, and if not, will just set the buffer to the
     * cached position. Otherwise it will serialise all the members into [backingBuffer].
     *
     * Returns true if [backingBuffer] has been updated, and false if not.
     */
    @Suppress("UNUSED_PARAMETER")
    fun populate(offset: Long = 0): Boolean {
        backingBuffer?.let { data ->
            val sizeRequired = if(sizeCached <= 0) {
                data.alignment
            } else {
                sizeCached + data.alignment
            }

            // check if we can fit this UBO, if not, resize it to 1.5x it's original size
            if(data.remaining() < sizeRequired) {
                data.resize()
            }

            return super.populate(data.buffer, -1L, elements = null)
        }

        return false
    }

    /**
     * Populates the [bufferView] with the members of this UBO, subject to the determined
     * sizes and alignments in a parallelized manner. A buffer [offset] can be given, as well as
     * a list of [elements], overriding the UBO's members. This routine checks via it's super
     * if an actual buffer update is required, and if not, will just set the buffer to the
     * cached position. Otherwise it will serialise all the members into [bufferView].
     *
     * Returns true if [bufferView] has been updated, and false if not.
     */
    fun populateParallel(bufferView: ByteBuffer, offset: Long, elements: LinkedHashMap<String, () -> Any>): Boolean {
        bufferView.position(0)
        bufferView.limit(bufferView.capacity())
        return super.populate(bufferView, offset, elements)
    }

    /**
     * Creates this UBO's members from the instancedProperties of [node].
     */
    fun fromInstance(node: InstancedNode.Instance) {
        node.instancedProperties.forEach { members.putIfAbsent(it.key, it.value) }
    }

    /**
     * Sets the [offset] of this UBO to the one from the [backingBuffer].
     */
    fun setOffsetFromBackingBuffer() {
        backingBuffer?.let {
            offset = it.advance()
        }
    }

    fun advanceBackingBuffer(): Int {
        if(backingBuffer == null) {
            throw IllegalStateException("Tried to advance buffer that has no backing buffer")
        }
        return backingBuffer.advance()
    }
}
