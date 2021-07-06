package graphics.scenery.backends.vulkan

import graphics.scenery.textures.Texture
import graphics.scenery.textures.Texture.BorderColor
import graphics.scenery.textures.UpdatableTexture.TextureExtents
import graphics.scenery.textures.Texture.RepeatMode
import graphics.scenery.textures.UpdatableTexture
import graphics.scenery.textures.UpdatableTexture.TextureUpdate
import graphics.scenery.utils.Image
import graphics.scenery.utils.LazyLogger
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkImageCreateInfo
import java.awt.color.ColorSpace
import java.awt.image.*
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.streams.toList

/**
 * Vulkan Texture class. Creates a texture on the [device], with [width]x[height]x[depth],
 * of [format], with a given number of [mipLevels]. Filtering can be set via
 * [minFilterLinear] and [maxFilterLinear]. Needs to be supplied with a [queue] to execute
 * generic operations on, and a [transferQueue] for transfer operations. Both are allowed to
 * be the same.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanTexture(val device: VulkanDevice,
                    val commandPools: VulkanRenderer.CommandPools, val queue: VkQueue, val transferQueue: VkQueue,
                    val width: Int, val height: Int, val depth: Int = 1,
                    val format: Int = VK_FORMAT_R8G8B8_SRGB, var mipLevels: Int = 1,
                    val minFilterLinear: Boolean = true, val maxFilterLinear: Boolean = true,
                    val usage: HashSet<Texture.UsageType> = hashSetOf(Texture.UsageType.Texture)) : AutoCloseable {
    //protected val logger by LazyLogger()

    private var initialised: Boolean = false

    /** The Vulkan image associated with this texture. */
    var image: VulkanImage
        protected set

    private var stagingImage: VulkanImage
    private var gt: Texture? = null

    var renderBarrier: VkImageMemoryBarrier? = null
        protected set

    /**
     * Wrapper class for holding on to raw Vulkan [image]s backed by [memory].
     */
    inner class VulkanImage(var image: Long = -1L, var memory: Long = -1L, val maxSize: Long = -1L) {

        /** Raw Vulkan sampler. */
        var sampler: Long = -1L
            internal set
        /** Raw Vulkan view. */
        var view: Long = -1L
            internal set

        /**
         * Copies the content of the image from [buffer]. This gets executed
         * within a given [commandBuffer].
         */
        fun copyFrom(commandBuffer: VkCommandBuffer, buffer: VulkanBuffer, update: TextureUpdate? = null, bufferOffset: Long = 0) {
            with(commandBuffer) {
                val bufferImageCopy = VkBufferImageCopy.calloc(1)

                bufferImageCopy.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
                bufferImageCopy.bufferOffset(bufferOffset)

                if(update != null) {
                    bufferImageCopy.imageExtent().set(update.extents.w, update.extents.h, update.extents.d)
                    bufferImageCopy.imageOffset().set(update.extents.x, update.extents.y, update.extents.z)
                } else {
                    bufferImageCopy.imageExtent().set(width, height, depth)
                    bufferImageCopy.imageOffset().set(0, 0, 0)
                }

                vkCmdCopyBufferToImage(this,
                    buffer.vulkanBuffer,
                    this@VulkanImage.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    bufferImageCopy)

                bufferImageCopy.free()
            }

            update?.let {
                it.consumed = true
            }
        }

        /**
         * Copies the content of the image to [buffer] from a series of [updates]. This gets executed
         * within a given [commandBuffer].
         */
        fun copyFrom(commandBuffer: VkCommandBuffer, buffer: VulkanBuffer, updates: List<TextureUpdate>, bufferOffset: Long = 0) {
            logger.debug("Got {} texture updates for {}", updates.size, this)
            with(commandBuffer) {
                val bufferImageCopy = VkBufferImageCopy.calloc(1)
                var offset = bufferOffset

                updates.forEach { update ->
                    val updateSize = update.contents.remaining()
                    bufferImageCopy.imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1)
                    bufferImageCopy.bufferOffset(offset)

                    bufferImageCopy.imageExtent().set(update.extents.w, update.extents.h, update.extents.d)
                    bufferImageCopy.imageOffset().set(update.extents.x, update.extents.y, update.extents.z)

                    vkCmdCopyBufferToImage(this,
                        buffer.vulkanBuffer,
                        this@VulkanImage.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        bufferImageCopy)

                    offset += updateSize
                    update.consumed = true
                }

                bufferImageCopy.free()
            }
        }

        /**
         * Copies the content of the image from a given [VulkanImage], [image].
         * This gets executed within a given [commandBuffer].
         */
        fun copyFrom(commandBuffer: VkCommandBuffer, image: VulkanImage, extents: TextureExtents? = null) {
            with(commandBuffer) {
                val subresource = VkImageSubresourceLayers.calloc()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseArrayLayer(0)
                    .mipLevel(0)
                    .layerCount(1)

                val region = VkImageCopy.calloc(1)
                    .srcSubresource(subresource)
                    .dstSubresource(subresource)

                if(extents != null) {
                    region.srcOffset().set(extents.x, extents.y, extents.z)
                    region.dstOffset().set(extents.x, extents.y, extents.z)
                    region.extent().set(extents.w, extents.h, extents.d)
                } else {
                    region.srcOffset().set(0, 0, 0)
                    region.dstOffset().set(0, 0, 0)
                    region.extent().set(width, height, depth)
                }

                vkCmdCopyImage(this,
                    image.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    this@VulkanImage.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region)

                subresource.free()
                region.free()
            }
        }

        override fun toString(): String {
            return "VulkanImage (${this.image.toHexString()}, ${width}x${height}x${depth}, format=$format, maxSize=${this.maxSize})"
        }
    }

    init {
        stagingImage = if(depth == 1) {
            createImage(width, height, depth,
                format, VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_IMAGE_TILING_LINEAR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                mipLevels = 1
            )
        } else {
            createImage(16, 16, 1,
                format, VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_IMAGE_TILING_LINEAR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                mipLevels = 1)
        }

        var usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT
        if(device.formatFeatureSupported(format, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, optimalTiling = true)) {
            usage = usage or VK_IMAGE_USAGE_STORAGE_BIT
        }

        image = createImage(width, height, depth,
            format, usage,
            VK_IMAGE_TILING_OPTIMAL, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            mipLevels)

        if (image.sampler == -1L) {
            image.sampler = createSampler()
        }

        if (image.view == -1L) {
            image.view = createImageView(image, format)
        }

        gt?.let { cache.put(it, this) }
    }

    /**
     * Alternative constructor to create a [VulkanTexture] from a [Texture].
     */
    constructor(device: VulkanDevice,
                commandPools: VulkanRenderer.CommandPools, queue: VkQueue, transferQueue: VkQueue,
                texture: Texture, mipLevels: Int = 1) : this(device,
        commandPools,
        queue,
        transferQueue,
        texture.dimensions.x().toInt(),
        texture.dimensions.y().toInt(),
        texture.dimensions.z().toInt(),
        texture.toVulkanFormat(),
        mipLevels, texture.minFilter == Texture.FilteringMode.Linear, texture.maxFilter == Texture.FilteringMode.Linear, usage = texture.usageType) {
        gt = texture
        gt?.let { cache.put(it, this) }
    }

    /**
     * Creates a Vulkan image of [format] with a given [width], [height], and [depth].
     * [usage] and [memoryFlags] need to be given, as well as the [tiling] parameter and number of [mipLevels].
     * A custom memory allocator may be used and given as [customAllocator].
     */
    fun createImage(width: Int, height: Int, depth: Int, format: Int,
                    usage: Int, tiling: Int, memoryFlags: Int, mipLevels: Int,
                    initialLayout: Int? = null,
                    customAllocator: ((VkMemoryRequirements, Long) -> Long)? = null, imageCreateInfo: VkImageCreateInfo? = null): VulkanImage {
        val imageInfo = if(imageCreateInfo != null) {
            imageCreateInfo
        } else {
            val i = VkImageCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(if (depth == 1) {
                    VK_IMAGE_TYPE_2D
                } else {
                    VK_IMAGE_TYPE_3D
                })
                .mipLevels(mipLevels)
                .arrayLayers(1)
                .format(format)
                .tiling(tiling)
                .initialLayout(if(depth == 1) {VK_IMAGE_LAYOUT_PREINITIALIZED} else { VK_IMAGE_LAYOUT_UNDEFINED })
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .flags(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT)

            i.extent().set(width, height, depth)
            i
        }

        if(initialLayout != null) {
            imageInfo.initialLayout(initialLayout)
        }

        val image = VU.getLong("create staging image",
            { vkCreateImage(device.vulkanDevice, imageInfo, null, this) }, {})

        val reqs = VkMemoryRequirements.calloc()
        vkGetImageMemoryRequirements(device.vulkanDevice, image, reqs)
        val memorySize = reqs.size()

        val memory = if(customAllocator == null) {
            val allocInfo = VkMemoryAllocateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(NULL)
                .allocationSize(memorySize)
                .memoryTypeIndex(device.getMemoryType(reqs.memoryTypeBits(), memoryFlags).first())

            VU.getLong("allocate image staging memory of size $memorySize",
                { vkAllocateMemory(device.vulkanDevice, allocInfo, null, this) },
                { imageInfo.free(); allocInfo.free() })
        } else {
            customAllocator.invoke(reqs, image)
        }

        reqs.free()

        vkBindImageMemory(device.vulkanDevice, image, memory, 0)

        return VulkanImage(image, memory, memorySize)
    }

    var tmpBuffer: VulkanBuffer? = null

    /**
     * Copies the data for this texture from a [ByteBuffer], [data].
     */
    fun copyFrom(data: ByteBuffer): VulkanTexture {
        if (depth == 1 && data.remaining() > stagingImage.maxSize) {
            logger.warn("Allocated image size for $this (${stagingImage.maxSize}) less than copy source size ${data.remaining()}.")
            return this
        }


        var deallocate = false
        var sourceBuffer = data

        gt?.let { gt ->
            if (gt.channels == 3) {
                logger.debug("Loading RGB texture, padding channels to 4 to fit RGBA")
                val pixelByteSize = when (gt.type) {
                    is UnsignedByteType -> 1
                    is ByteType -> 1
                    is UnsignedShortType -> 2
                    is ShortType -> 2
                    is UnsignedIntType -> 4
                    is IntType -> 4
                    is FloatType -> 4
                    is DoubleType -> 8
                    else -> throw UnsupportedOperationException("Don't know how to handle textures of type ${gt.type.javaClass.simpleName}")
                }

                val storage = memAlloc(data.remaining() / 3 * 4)
                val view = data.duplicate()
                val tmp = ByteArray(pixelByteSize * 3)
                val alpha = (0 until pixelByteSize).map { 255.toByte() }.toByteArray()

                // pad buffer to 4 channels
                while (view.hasRemaining()) {
                    view.get(tmp, 0, 3)
                    storage.put(tmp)
                    storage.put(alpha)
                }

                storage.flip()
                deallocate = true
                sourceBuffer = storage
            } else {
                deallocate = false
                sourceBuffer = data
            }
        }

        logger.debug("Updating {} with {} miplevels", this, mipLevels)
        if (mipLevels == 1) {
            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                if(!initialised) {
                    transitionLayout(stagingImage.image,
                        VK_IMAGE_LAYOUT_PREINITIALIZED,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mipLevels,
                        srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        commandBuffer = this)
                }

                if (depth == 1) {
                    val dest = memAllocPointer(1)
                    vkMapMemory(device, stagingImage.memory, 0, sourceBuffer.remaining() * 1L, 0, dest)
                    memCopy(memAddress(sourceBuffer), dest.get(0), sourceBuffer.remaining().toLong())
                    vkUnmapMemory(device, stagingImage.memory)
                    memFree(dest)

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mipLevels,
                        srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        commandBuffer = this)

                    image.copyFrom(this, stagingImage)

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, mipLevels,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        commandBuffer = this)

                } else {
                    val genericTexture = gt
                    val requiredCapacity = if(genericTexture is UpdatableTexture && genericTexture.hasConsumableUpdates()) {
                        genericTexture.getConsumableUpdates().map { it.contents.remaining() }.sum().toLong()
                    } else {
                        sourceBuffer.capacity().toLong()
                    }

                    logger.debug("{} has {} consumeable updates", this@VulkanTexture, (genericTexture as? UpdatableTexture)?.getConsumableUpdates()?.size)

                    if(tmpBuffer == null || (tmpBuffer?.size ?: 0) < requiredCapacity) {
                        logger.debug("(${this@VulkanTexture}) Reallocating tmp buffer, old size=${tmpBuffer?.size} new size = ${requiredCapacity.toFloat()/1024.0f/1024.0f} MiB")
                        tmpBuffer?.close()
                        // reserve a bit more space if the texture is small, to avoid reallocations
                        val reservedSize = if(requiredCapacity < 1024*1024*8) {
                            (requiredCapacity * 1.33).roundToLong()
                        } else {
                            requiredCapacity
                        }

                        tmpBuffer = VulkanBuffer(this@VulkanTexture.device,
                            max(reservedSize, 1024*1024),
                            VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            wantAligned = false)
                    }

                    tmpBuffer?.let { buffer ->
                        transitionLayout(image.image,
                            VK_IMAGE_LAYOUT_UNDEFINED,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mipLevels,
                            srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            commandBuffer = this)

                        if(genericTexture is UpdatableTexture) {
                            if(genericTexture.hasConsumableUpdates()) {
                                val contents = genericTexture.getConsumableUpdates().map { it.contents }

                                buffer.copyFrom(contents, keepMapped = true)
                                image.copyFrom(this, buffer, genericTexture.getConsumableUpdates())

                                genericTexture.clearConsumedUpdates()
                            } /*else {
                                // TODO: Semantics, do we want UpdateableTextures to be only
                                // updateable via updates, or shall they read from buffer on first init?
                                buffer.copyFrom(sourceBuffer)
                                image.copyFrom(this, buffer)
                            }*/
                        } else {
                            buffer.copyFrom(sourceBuffer)
                            image.copyFrom(this, buffer)
                        }

                        transitionLayout(image.image,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, mipLevels,
                            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                            commandBuffer = this)
                    }

                }

                endCommandBuffer(this@VulkanTexture.device, commandPools.Standard, transferQueue, flush = true, dealloc = true, block = true)
            }
        } else {
            val buffer = VulkanBuffer(device,
                sourceBuffer.limit().toLong(),
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                wantAligned = false)

            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                buffer.copyFrom(sourceBuffer)

                transitionLayout(image.image, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, commandBuffer = this,
                    srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)
                image.copyFrom(this, buffer)
                transitionLayout(image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1, commandBuffer = this,
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)

                endCommandBuffer(this@VulkanTexture.device, commandPools.Standard, transferQueue, flush = true, dealloc = true, block = true)
            }

            val imageBlit = VkImageBlit.calloc(1)
            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) mipmapCreation@{

                for (mipLevel in 1 until mipLevels) {
                    imageBlit.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, mipLevel - 1, 0, 1)
                    imageBlit.srcOffsets(1).set(width shr (mipLevel - 1), height shr (mipLevel - 1), 1)

                    val dstWidth = width shr mipLevel
                    val dstHeight = height shr mipLevel

                    if (dstWidth < 2 || dstHeight < 2) {
                        break
                    }

                    imageBlit.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, mipLevel, 0, 1)
                    imageBlit.dstOffsets(1).set(width shr (mipLevel), height shr (mipLevel), 1)

                    val mipSourceRange = VkImageSubresourceRange.calloc()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseArrayLayer(0)
                        .layerCount(1)
                        .baseMipLevel(mipLevel - 1)
                        .levelCount(1)

                    val mipTargetRange = VkImageSubresourceRange.calloc()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseArrayLayer(0)
                        .layerCount(1)
                        .baseMipLevel(mipLevel)
                        .levelCount(1)

                    if (mipLevel > 1) {
                        transitionLayout(image.image,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            subresourceRange = mipSourceRange,
                            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            commandBuffer = this@mipmapCreation)
                    }

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        subresourceRange = mipTargetRange,
                        srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        commandBuffer = this@mipmapCreation)

                    vkCmdBlitImage(this@mipmapCreation,
                        image.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        imageBlit, VK_FILTER_LINEAR)

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, subresourceRange = mipSourceRange,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        commandBuffer = this@mipmapCreation)

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, subresourceRange = mipTargetRange,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        commandBuffer = this@mipmapCreation)

                    mipSourceRange.free()
                    mipTargetRange.free()
                }

                this@mipmapCreation.endCommandBuffer(this@VulkanTexture.device, commandPools.Standard, queue, flush = true, dealloc = true)
            }

            imageBlit.free()
            buffer.close()
        }

        // deallocate in case we moved pixels around
        if (deallocate) {
            memFree(sourceBuffer)
        }

//        image.view = createImageView(image, format)

        initialised = true
        return this
    }

    /**
     * Copies the first layer, first mipmap of the texture to [buffer].
     */
    fun copyTo(buffer: ByteBuffer) {
        if(tmpBuffer == null || (tmpBuffer != null && tmpBuffer?.size!! < image.maxSize)) {
            tmpBuffer?.close()
            tmpBuffer = VulkanBuffer(this@VulkanTexture.device,
                image.maxSize,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                wantAligned = false)
        }

        tmpBuffer?.let { b ->
            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                transitionLayout(image.image,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1,
                    srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                    commandBuffer = this)

                val type = VK_IMAGE_ASPECT_COLOR_BIT

                val subresource = VkImageSubresourceLayers.calloc()
                    .aspectMask(type)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)

                val regions = VkBufferImageCopy.calloc(1)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageOffset(VkOffset3D.calloc().set(0, 0, 0))
                    .imageExtent(VkExtent3D.calloc().set(width, height, depth))
                    .imageSubresource(subresource)

                vkCmdCopyImageToBuffer(
                    this,
                    image.image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    b.vulkanBuffer,
                    regions
                )

                transitionLayout(image.image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 1,
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    commandBuffer = this)

                endCommandBuffer(this@VulkanTexture.device, commandPools.Standard, transferQueue, flush = true, dealloc = true, block = true)
            }

            b.copyTo(buffer)
        }
    }

    /**
     * Creates a Vulkan image view with [format] for an [image].
     */
    fun createImageView(image: VulkanImage, format: Int): Long {
        if(image.view != -1L) {
            vkDestroyImageView(device.vulkanDevice, image.view, null)
        }

        val subresourceRange = VkImageSubresourceRange.calloc().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, mipLevels, 0, 1)

        val vi = VkImageViewCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .pNext(NULL)
            .image(image.image)
            .viewType(if (depth > 1) {
                VK_IMAGE_VIEW_TYPE_3D
            } else {
                VK_IMAGE_VIEW_TYPE_2D
            })
            .format(format)
            .subresourceRange(subresourceRange)

        if(gt?.channels == 1 && depth > 1) {
            vi.components().set(VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R)
        }

        return VU.getLong("Creating image view",
            { vkCreateImageView(device.vulkanDevice, vi, null, this) },
            { vi.free(); subresourceRange.free(); })
    }

    private fun RepeatMode.toVulkan(): Int {
        return when(this) {
            RepeatMode.Repeat -> VK_SAMPLER_ADDRESS_MODE_REPEAT
            RepeatMode.MirroredRepeat -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
            RepeatMode.ClampToEdge -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
            RepeatMode.ClampToBorder -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER
        }
    }

    private fun BorderColor.toVulkan(type: NumericType<*>): Int {
        var color = when(this) {
            BorderColor.TransparentBlack -> VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
            BorderColor.OpaqueBlack -> VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK
            BorderColor.OpaqueWhite -> VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE
        }

        if(type !is FloatType) {
            color += 1
        }

        return color
    }

    /**
     * Creates a default sampler for this texture.
     */
    fun createSampler(texture: Texture? = null): Long {
        val t = texture ?: gt

        val (repeatS, repeatT, repeatU) = if(t != null) {
            Triple(
                t.repeatUVW.first.toVulkan(),
                t.repeatUVW.second.toVulkan(),
                t.repeatUVW.third.toVulkan()
            )
        } else {
            if(depth == 1) {
                Triple(VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_REPEAT)
            } else {
                Triple(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            }
        }

        val samplerInfo = VkSamplerCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .pNext(NULL)
            .magFilter(if(minFilterLinear) { VK_FILTER_LINEAR } else { VK_FILTER_NEAREST })
            .minFilter(if(maxFilterLinear) { VK_FILTER_LINEAR } else { VK_FILTER_NEAREST })
            .mipmapMode(if(depth == 1) { VK_SAMPLER_MIPMAP_MODE_LINEAR } else { VK_SAMPLER_MIPMAP_MODE_NEAREST })
            .addressModeU(repeatS)
            .addressModeV(repeatT)
            .addressModeW(repeatU)
            .mipLodBias(0.0f)
            .anisotropyEnable(depth == 1)
            .maxAnisotropy(if(depth == 1) { 8.0f } else { 1.0f })
            .minLod(0.0f)
            .maxLod(if(depth == 1) {mipLevels * 1.0f} else { 0.0f })
            .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
            .compareOp(VK_COMPARE_OP_NEVER)

        if(t != null) {
            samplerInfo.borderColor(t.borderColor.toVulkan(t.type))
        }

        val sampler = VU.getLong("creating sampler",
            { vkCreateSampler(device.vulkanDevice, samplerInfo, null, this) },
            { samplerInfo.free() })

        logger.debug("Created sampler {}", sampler.toHexString().toLowerCase())
        val oldSampler = image.sampler
        image.sampler = sampler
        if(oldSampler != -1L) {
            vkDestroySampler(device.vulkanDevice, oldSampler, null)
        }
        return sampler
    }

    override fun toString(): String {
        return "VulkanTexture on $device (${this.image.image.toHexString()}, ${width}x${height}x$depth, format=${this.format}, mipLevels=${mipLevels}, gt=${this.gt != null} minFilter=${this.minFilterLinear} maxFilter=${this.maxFilterLinear})"
    }

    /**
     * Deallocates and destroys this [VulkanTexture] instance, freeing all memory
     * related to it.
     */
    override fun close() {
        gt?.let { cache.remove(it) }

        if (image.view != -1L) {
            vkDestroyImageView(device.vulkanDevice, image.view, null)
            image.view = -1L
        }

        if (image.image != -1L) {
            vkDestroyImage(device.vulkanDevice, image.image, null)
            image.image = -1L
        }

        if (image.sampler != -1L) {
            vkDestroySampler(device.vulkanDevice, image.sampler, null)
            image.sampler = -1L
        }

        if (image.memory != -1L) {
            vkFreeMemory(device.vulkanDevice, image.memory, null)
            image.memory = -1L
        }

        if (stagingImage.image != -1L) {
            vkDestroyImage(device.vulkanDevice, stagingImage.image, null)
            stagingImage.image = -1L
        }

        if (stagingImage.memory != -1L) {
            vkFreeMemory(device.vulkanDevice, stagingImage.memory, null)
            stagingImage.memory = -1L
        }

        tmpBuffer?.close()
    }


    /**
     * Utility methods for [VulkanTexture].
     */
    companion object {
        @JvmStatic private val logger by LazyLogger()

        private val cache = HashMap<Texture, VulkanTexture>()

        private val StandardAlphaColorModel = ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            intArrayOf(8, 8, 8, 8),
            true,
            false,
            ComponentColorModel.TRANSLUCENT,
            DataBuffer.TYPE_BYTE)

        private val StandardColorModel = ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            intArrayOf(8, 8, 8, 0),
            false,
            false,
            ComponentColorModel.OPAQUE,
            DataBuffer.TYPE_BYTE)

        fun getReference(texture: Texture): VulkanTexture? {
            return cache.get(texture)
        }

        /**
         * Loads a texture from a file given by [filename], and allocates the [VulkanTexture] on [device].
         */
        fun loadFromFile(device: VulkanDevice,
                         commandPools: VulkanRenderer.CommandPools , queue: VkQueue, transferQueue: VkQueue,
                         filename: String,
                         linearMin: Boolean, linearMax: Boolean,
                         generateMipmaps: Boolean = true): VulkanTexture {
            val stream = FileInputStream(filename)
            val type = filename.substringAfterLast('.')


            logger.debug("Loading${if(generateMipmaps) { " mipmapped" } else { "" }} texture from $filename")

            return if(type == "raw") {
                val path = Paths.get(filename)
                val infoFile = path.resolveSibling(path.fileName.toString().substringBeforeLast(".") + ".info")
                val dimensions = Files.lines(infoFile).toList().first().split(",").map { it.toLong() }.toLongArray()

                loadFromFileRaw(device,
                        commandPools, queue, transferQueue,
                        stream, type, dimensions)
            } else {
                loadFromFile(device,
                        commandPools, queue, transferQueue,
                        stream, type, linearMin, linearMax, generateMipmaps)
            }
        }

        /**
         * Loads a texture from a file given by a [stream], and allocates the [VulkanTexture] on [device].
         */
        fun loadFromFile(device: VulkanDevice,
                         commandPools: VulkanRenderer.CommandPools, queue: VkQueue, transferQueue: VkQueue,
                         stream: InputStream, type: String,
                         linearMin: Boolean, linearMax: Boolean,
                         generateMipmaps: Boolean = true): VulkanTexture {
            val image = Image.fromStream(stream, type, true)

            var texWidth = 2
            var texHeight = 2
            var levelsW = 1
            var levelsH = 1

            while (texWidth < image.width) {
                texWidth *= 2
                levelsW++
            }
            while (texHeight < image.height) {
                texHeight *= 2
                levelsH++
            }

            val mipmapLevels = if(generateMipmaps) {
                Math.min(levelsW, levelsH)
            } else {
                1
            }

            val tex = VulkanTexture(
                device,
                commandPools, queue, transferQueue,
                texWidth, texHeight, 1,
                VK_FORMAT_R8G8B8A8_SRGB,
                mipmapLevels,
                linearMin, linearMax)

            tex.copyFrom(image.contents)

            return tex
        }

        /**
         * Loads a texture from a raw file given by a [stream], and allocates the [VulkanTexture] on [device].
         */
        @Suppress("UNUSED_PARAMETER")
        fun loadFromFileRaw(device: VulkanDevice,
                            commandPools: VulkanRenderer.CommandPools, queue: VkQueue, transferQueue: VkQueue,
                            stream: InputStream, type: String, dimensions: LongArray): VulkanTexture {
            val imageData: ByteBuffer = ByteBuffer.allocateDirect((2 * dimensions[0] * dimensions[1] * dimensions[2]).toInt())
            val buffer = ByteArray(1024*1024)

            var bytesRead = stream.read(buffer)
            while(bytesRead > -1) {
                imageData.put(buffer)
                bytesRead = stream.read(buffer)
            }

            val tex = VulkanTexture(
                device,
                commandPools, queue, transferQueue,
                dimensions[0].toInt(), dimensions[1].toInt(), dimensions[2].toInt(),
                VK_FORMAT_R16_UINT, 1, true, true)

            tex.copyFrom(imageData)

            stream.close()

            return tex
        }

        /**
         * Transitions Vulkan image layouts, with [srcAccessMask] and [dstAccessMask] explicitly specified.
         */
        fun transitionLayout(image: Long, from: Int, to: Int, mipLevels: Int = 1,
                             subresourceRange: VkImageSubresourceRange? = null,
                             srcStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             srcAccessMask: Int, dstAccessMask: Int,
                             commandBuffer: VkCommandBuffer, dependencyFlags: Int = 0, memoryBarrier: Boolean = false) {
            stackPush().use { stack ->
                val barrier = VkImageMemoryBarrier.callocStack(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .pNext(NULL)
                    .oldLayout(from)
                    .newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(srcAccessMask)
                    .dstAccessMask(dstAccessMask)
                    .image(image)

                if (subresourceRange == null) {
                    barrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(mipLevels)
                        .baseArrayLayer(0)
                        .layerCount(1)
                } else {
                    barrier.subresourceRange(subresourceRange)
                }

                logger.trace("Transition: {} -> {} with srcAccessMark={}, dstAccessMask={}, srcStage={}, dstStage={}", from, to, barrier.srcAccessMask(), barrier.dstAccessMask(), srcStage, dstStage)

                val memoryBarriers = if(memoryBarrier) {
                    VkMemoryBarrier.callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(srcAccessMask)
                        .dstAccessMask(dstAccessMask)
                } else {
                    null
                }

                vkCmdPipelineBarrier(
                    commandBuffer,
                    srcStage,
                    dstStage,
                    dependencyFlags,
                    memoryBarriers,
                    null,
                    barrier
                )
            }
        }

        /**
         * Transitions Vulkan image layouts.
         */
        fun transitionLayout(image: Long, oldLayout: Int, newLayout: Int, mipLevels: Int = 1,
                             subresourceRange: VkImageSubresourceRange? = null,
                             srcStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             commandBuffer: VkCommandBuffer) {

            with(commandBuffer) {
                val barrier = VkImageMemoryBarrier.calloc(1)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .pNext(NULL)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)

                if (subresourceRange == null) {
                    barrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(mipLevels)
                        .baseArrayLayer(0)
                        .layerCount(1)
                } else {
                    barrier.subresourceRange(subresourceRange)
                }

                if (oldLayout == VK_IMAGE_LAYOUT_PREINITIALIZED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                    barrier
                        .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                } else if (oldLayout == VK_IMAGE_LAYOUT_PREINITIALIZED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    barrier
                        .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    barrier
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    if(dstStage == VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT) {
                        barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
                    } else {
                        barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    }
                } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    barrier
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    barrier
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                } else if(oldLayout == KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                    barrier
                        .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && newLayout == KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
                    barrier
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    barrier
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_INPUT_ATTACHMENT_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                } else if(oldLayout == KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && newLayout == KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                } else if(oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                } else {
                    logger.error("Unsupported layout transition: $oldLayout -> $newLayout")
                }

                logger.trace("Transition: {} -> {} with srcAccessMark={}, dstAccessMask={}, srcStage={}, dstStage={}", oldLayout, newLayout, barrier.srcAccessMask(), barrier.dstAccessMask(), srcStage, dstStage)

                vkCmdPipelineBarrier(this,
                    srcStage,
                    dstStage,
                    0, null, null, barrier)

                barrier.free()
            }
        }

        private fun Texture.toVulkanFormat(): Int {
            var format = when(this.type) {
                is ByteType -> when(this.channels) {
                    1 -> VK_FORMAT_R8_SNORM
                    2 -> VK_FORMAT_R8G8_SNORM
                    3 -> VK_FORMAT_R8G8B8A8_SNORM
                    4 -> VK_FORMAT_R8G8B8A8_SNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is UnsignedByteType -> when(this.channels) {
                    1 -> VK_FORMAT_R8_UNORM
                    2 -> VK_FORMAT_R8G8_UNORM
                    3 -> VK_FORMAT_R8G8B8A8_UNORM
                    4 -> VK_FORMAT_R8G8B8A8_UNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is ShortType ->  when(this.channels) {
                    1 -> VK_FORMAT_R16_SNORM
                    2 -> VK_FORMAT_R16G16_SNORM
                    3 -> VK_FORMAT_R16G16B16A16_SNORM
                    4 -> VK_FORMAT_R16G16B16A16_SNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is UnsignedShortType -> when(this.channels) {
                    1 -> VK_FORMAT_R16_UNORM
                    2 -> VK_FORMAT_R16G16_UNORM
                    3 -> VK_FORMAT_R16G16B16A16_UNORM
                    4 -> VK_FORMAT_R16G16B16A16_UNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is IntType ->  when(this.channels) {
                    1 -> VK_FORMAT_R32_SINT
                    2 -> VK_FORMAT_R32G32_SINT
                    3 -> VK_FORMAT_R32G32B32A32_SINT
                    4 -> VK_FORMAT_R32G32B32A32_SINT

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is UnsignedIntType ->  when(this.channels) {
                    1 -> VK_FORMAT_R32_UINT
                    2 -> VK_FORMAT_R32G32_UINT
                    3 -> VK_FORMAT_R32G32B32A32_UINT
                    4 -> VK_FORMAT_R32G32B32A32_UINT

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is FloatType ->  when(this.channels) {
                    1 -> VK_FORMAT_R32_SFLOAT
                    2 -> VK_FORMAT_R32G32_SFLOAT
                    3 -> VK_FORMAT_R32G32B32A32_SFLOAT
                    4 -> VK_FORMAT_R32G32B32A32_SFLOAT

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is DoubleType -> TODO("Double format textures are not supported")

                else -> throw UnsupportedOperationException("Type ${type.javaClass.simpleName} is not supported")
            }

            if(!this.normalized && this.type !is FloatType && this.type !is ByteType && this.type !is IntType) {
                format += 4
            }

            return format
        }
    }
}
