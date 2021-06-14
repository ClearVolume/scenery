package graphics.scenery.backends.vulkan

import graphics.scenery.InstancedNode
import graphics.scenery.backends.UBO
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import java.nio.IntBuffer

/** Magic value to use for unintialised buffer offsets. */
const val BUFFER_OFFSET_UNINTIALISED = -1337

/**
 * UBO class for Vulkan, providing specific functionality, such as buffer making and UBO buffer creation.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanUBO(val device: VulkanDevice, var backingBuffer: VulkanBuffer? = null): AutoCloseable, UBO() {
    /** [UBODescriptor] for this UBO, containing size, memory pointer, etc. */
    var descriptor = UBODescriptor()
        private set
    /** Offsets for this UBO, with respect to the backing buffer. */
    var offsets: IntBuffer = memAllocInt(1).put(0, BUFFER_OFFSET_UNINTIALISED)
    private var closed = false
    private var ownedBackingBuffer: VulkanBuffer? = null
    private var stagingMemory: ByteBuffer? = null

    /**
     * UBO descriptor class, wrapping memory pointers,
     * allocation sizes, offsets and ranges for the backing buffer.
     */
    class UBODescriptor {
        internal var memory: Long = 0
        internal var allocationSize: Long = 0
        internal var buffer: Long = 0
        internal var offset: Long = 0
        internal var range: Long = 0
    }

    protected fun copy(data: ByteBuffer, offset: Long = 0) {
        val dest = memAllocPointer(1)

        VU.run("Mapping buffer memory/vkMapMemory", { vkMapMemory(device.vulkanDevice, descriptor.memory, offset, descriptor.allocationSize* 1L, 0, dest) })
        memCopy(memAddress(data), dest.get(0), data.remaining().toLong())

        vkUnmapMemory(device.vulkanDevice, descriptor.memory)
        memFree(dest)
    }

    /**
     * Populates the [backingBuffer] with the members of this UBO, subject to the determined
     * sizes and alignments. A buffer [offset] can be given. This routine checks via it's super
     * if an actual buffer update is required, and if not, will just set the buffer to the
     * cached position. Otherwise it will serialise all the members into [backingBuffer].
     *
     * Returns true if [backingBuffer] has been updated, and false if not.
     */
    fun populate(offset: Long = 0L): Boolean {
        val updated: Boolean
        val buffer = backingBuffer

        if(buffer == null) {
            val data = stagingMemory ?: memAlloc(getSize())
            stagingMemory = data

            updated = super.populate(data, offset, elements = null)

            data.flip()
            copy(data, offset = offset)
        } else {
            val sizeRequired = if(sizeCached <= 0) {
                buffer.alignment
            } else {
                sizeCached + buffer.alignment
            }

            if(buffer.stagingBuffer.remaining() < sizeRequired) {
                logger.debug("Resizing $buffer from ${buffer.size} to ${buffer.size*1.5}")
                buffer.resize()
            }

            updated = super.populate(buffer.stagingBuffer, offset, elements = null)
        }

        return updated
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
     * Creates a [UBODescriptor] for this UBO and returns it.
     */
    fun createUniformBuffer(): UBODescriptor {
        backingBuffer?.let { buffer ->
            descriptor.memory = buffer.memory
            descriptor.allocationSize = buffer.size
            descriptor.buffer = buffer.vulkanBuffer
            descriptor.offset = 0L
            descriptor.range = this.getSize() * 1L

            return descriptor
        }

        ownedBackingBuffer = VulkanBuffer(device,
            this.getSize() * 1L,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            wantAligned = true)

        ownedBackingBuffer?.let { buffer ->
            descriptor = UBODescriptor()
            descriptor.memory = buffer.memory
            descriptor.allocationSize = buffer.size
            descriptor.buffer = buffer.vulkanBuffer
            descriptor.offset = 0L
            descriptor.range = this.getSize() * 1L
        }

        return descriptor
    }

    /**
     * Updates this buffer to use a new [backingBuffer] buffer,
     * given as [newBackingBuffer].
     */
    @Suppress("unused")
    fun updateBackingBuffer(newBackingBuffer: VulkanBuffer) {
        descriptor.memory = newBackingBuffer.memory
        descriptor.allocationSize = newBackingBuffer.size
        descriptor.buffer = newBackingBuffer.vulkanBuffer
        descriptor.offset = 0L
        descriptor.range = this.getSize() * 1L

        backingBuffer = newBackingBuffer
    }

    /**
     * Copies the backing buffer's staging buffer content to the actual backing buffer,
     * used for RAM -> GPU copies.
     */
    @Suppress("unused")
    fun copyFromStagingBuffer() {
        backingBuffer?.copyFromStagingBuffer()
    }

    /**
     * Closes this UBO, freeing staging memory and closing any self-owned backing buffers.
     */
    override fun close() {
        if(closed) {
            return
        }

        logger.trace("Closing UBO $this ...")
        if(backingBuffer == null) {
            ownedBackingBuffer?.let {
                logger.trace("Destroying self-owned buffer of $this/$it  ${it.memory.toHexString()})...")
                it.close()
            }
        }

        stagingMemory?.let {
            memFree(it)
        }

        memFree(offsets)
        closed = true
    }
}
