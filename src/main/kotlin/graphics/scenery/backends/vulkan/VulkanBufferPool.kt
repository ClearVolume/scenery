package graphics.scenery.backends.vulkan

import graphics.scenery.utils.LazyLogger
import org.lwjgl.vulkan.VK10
import vkk.VkBufferUsage
import vkk.VkBufferUsageFlags
import vkk.entities.VkDeviceSize
import vkk.or
import java.util.concurrent.CopyOnWriteArrayList


/** Default [VulkanBufferPool] backing store size. */
val basicBufferSize = VkDeviceSize(1024 * 1024 * 32)

/**
 * Represents a pool of [VulkanBuffer]s, from which [VulkanSuballocation]s can be made.
 * Each buffer pool resides on a [device] and has specific [usage] flags, e.g. for vertex
 * or texture storage.
 */
class VulkanBufferPool(val device: VulkanDevice,
                       val usage: VkBufferUsageFlags = VkBufferUsage.VERTEX_BUFFER_BIT or VkBufferUsage.INDEX_BUFFER_BIT or VkBufferUsage.TRANSFER_DST_BIT,
                       val bufferSize: VkDeviceSize = basicBufferSize) {

    private val logger by LazyLogger()
    protected val backingStore = CopyOnWriteArrayList<VulkanBufferAllocation>()

    /**
     * Creates a new [VulkanSuballocation] of a given [size]. If the allocation cannot be made with
     * the current set of buffers in [backingStore], a new buffer will be added.
     */
    @Synchronized
    fun create(size: Int): VulkanSuballocation {
        val options = backingStore.filter { it.usage == usage && it.fit(size) != null }

        return if (options.isEmpty()) {
            logger.trace("Could not find space for allocation of {}, creating new buffer", size)
            var bufferSize = this.bufferSize
            while (bufferSize < size) {
                bufferSize *= 2
            }

            // increase size for new backing store members in case we already have a few,
            // to limit the number of necessary buffers
            if (bufferSize == this.bufferSize && backingStore.size > 4) {
                bufferSize *= 4
            }

            val vb = VulkanBuffer(device, bufferSize, usage, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true)
            val alloc = VulkanBufferAllocation(usage, vb.allocatedSize, vb, vb.alignment)
            backingStore.add(alloc)
            logger.trace("Added new buffer of size {} to backing store", bufferSize)

            val suballocation = alloc.allocate(alloc.fit(size)
                ?: throw IllegalStateException("New allocation of ${vb.allocatedSize} cannot fit $size"))

            suballocation
        } else {
            val suballocation = options.first().fit(size) ?: throw IllegalStateException("Suballocation vanished")
            options.first().allocate(suballocation)

            suballocation
        }
    }

    /**
     * Creates a new [VulkanBuffer] of [size], backed by this [VulkanBufferPool].
     */
    fun createBuffer(size: Int): VulkanBuffer {
        return VulkanBuffer.fromPool(this, size.toLong())
    }

    /**
     * Returns a string representation of this pool.
     */
    override fun toString(): String {
        return backingStore.mapIndexed { i, it ->
            "Backing store buffer $i: $it"
        }.joinToString("\n")
    }
}

