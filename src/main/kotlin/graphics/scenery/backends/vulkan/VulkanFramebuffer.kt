package graphics.scenery.backends.vulkan

import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Struct
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.LongBuffer
import java.util.*


/**
 * Vulkan Framebuffer class. Creates a framebuffer on [device], associated with
 * a [commandPool]. The framebuffer's [width] and [height] need to be given, as well
 * as a [commandBuffer] during which's execution the framebuffer will be created.
 *
 * [shouldClear] - set if on the beginning of a render pass, the framebuffer should be cleared. On by default.
 * [sRGB] - set if sRGB targets should be used. Off by default.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanFramebuffer(protected val device: VulkanDevice,
                             protected var commandPool: Long,
                             var width: Int,
                             var height: Int,
                             val commandBuffer: VkCommandBuffer,
                             var shouldClear: Boolean = true, val sRGB: Boolean = false): AutoCloseable {
    protected val logger by LazyLogger()

    /** Raw Vulkan framebuffer reference. */
    var framebuffer = memAllocLong(1)
        protected set

    /** Raw Vulkan renderpass reference. */
    var renderPass = memAllocLong(1)
        protected set

    /* Raw Vulkan sampler reference. */
    var framebufferSampler = memAllocLong(1)
        protected set

    /** Descriptor set for this framebuffer's output.  */
    var outputDescriptorSetLayout: Long = -1L
        internal set

    /** Descriptor set for this framebuffer's output.  */
    var outputDescriptorSet: Long = -1L
        internal set

    /** Descriptor set for this framebuffer's output.  */
    var imageLoadStoreDescriptorSetLayout: Long = -1L
        internal set

    /** Descriptor set for this framebuffer's output.  */
    var imageLoadStoreDescriptorSet: Long = -1L
        internal set

    /** Flag to indicate whether this framebuffer has been initialiased or not. */
    protected var initialized: Boolean = false

    /** Enum class for indicating whether a framebuffer containts a color or a depth attachment. */
    enum class VulkanFramebufferType { COLOR_ATTACHMENT, DEPTH_ATTACHMENT }

    /** Class to describe framebuffer attachments */
    inner class VulkanFramebufferAttachment: AutoCloseable {
        /** Image reference for the attachment */
        var image: Long = -1L
        /** Memory reference for the attachment */
        var memory: LongBuffer = memAllocLong(1)
        /** Image view for the attachment */
        var imageView: LongBuffer = memAllocLong(1)
        /** Vulkan format of this attachment */
        var format: Int = 0
        /** Descriptor set for this attachment */
        var descriptorSetLayout: Long = -1L
        /** Descriptor set for this attachment */
        var descriptorSet: Long = -1L
        /** Descriptor set to use this attachment for image load/store */
        var loadStoreDescriptorSetLayout: Long? = null
        /** Descriptor set to use this attachment for image load/store */
        var loadStoreDescriptorSet: Long? = null


        /** Attachment type */
        var type: VulkanFramebufferType = VulkanFramebufferType.COLOR_ATTACHMENT

        /** Vulkan attachment description */
        var desc: VkAttachmentDescription = VkAttachmentDescription.calloc()

        /**
         * Indicates whether the image for this attachment comes from a swapchain image,
         * in which case a dedicated allocation is not necessary.
         */
        var fromSwapchain = false

        init {
            memory.put(0, -1L)
        }

        /**
         * Closes the attachment, freeing its resources.
         */
        override fun close() {
            if(descriptorSetLayout != -1L) {
                device.removeDescriptorSetLayout(descriptorSetLayout)
            }
            loadStoreDescriptorSetLayout?.let {
                if (loadStoreDescriptorSetLayout != -1L) {
                    device.removeDescriptorSetLayout(it)
                }
            }

            vkDestroyImageView(device.vulkanDevice, imageView.get(0), null)
            memFree(imageView)

            if(image != -1L && fromSwapchain == false) {
                vkDestroyImage(device.vulkanDevice, image, null)
            }

            if(memory.get(0) != -1L) {
                vkFreeMemory(device.vulkanDevice, memory.get(0), null)
            }

            memFree(memory)

            desc.free()
        }

        fun compatibleWith(thisFramebuffer: VulkanFramebuffer, other: VulkanFramebufferAttachment, otherFramebuffer: VulkanFramebuffer): Boolean {
            return (this.format == other.format
                && this.type == other.type
                && thisFramebuffer.width == otherFramebuffer.width
                && thisFramebuffer.height == otherFramebuffer.height
                && thisFramebuffer.sRGB == otherFramebuffer.sRGB)
        }
    }

    /** Linked hash map of this framebuffer's [VulkanFramebufferAttachment]s. */
    var attachments = LinkedHashMap<String, VulkanFramebufferAttachment>()

    init {
        val samplerCreateInfo = VkSamplerCreateInfo.calloc()
            .default()
            .magFilter(VK_FILTER_LINEAR)
            .minFilter(VK_FILTER_LINEAR)
            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .mipLodBias(0.0f)
            .maxAnisotropy(1.0f)
            .minLod(0.0f)
            .maxLod(1.0f)
            .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)

        vkCreateSampler(device.vulkanDevice, samplerCreateInfo, null, this.framebufferSampler)

        samplerCreateInfo.free()
    }

    /**
     * Internal function to create attachments of [format], with image usage flags given in [usage].
     * The attachment will have dimensions [attachmentWidth] x [attachmentHeight].
     *
     * This function also creates the necessary images, memory allocs, and image views.
     */
    protected fun createAttachment(format: Int, usage: Int, attachmentWidth: Int = width, attachmentHeight: Int = height, name: String = ""): VulkanFramebufferAttachment {
        val a = VulkanFramebufferAttachment()
        var aspectMask: Int = 0
        var imageLayout: Int = 0
        var loadStoreSupported = false

        a.format = format

        if (usage == VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) {
            aspectMask = VK_IMAGE_ASPECT_COLOR_BIT
            imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        }

        if (usage == VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) {
            aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT
            imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        }

        val imageExtent = VkExtent3D.calloc()
            .width(attachmentWidth)
            .height(attachmentHeight)
            .depth(1)

        val image = VkImageCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .pNext(NULL)
            .imageType(VK_IMAGE_TYPE_2D)
            .format(a.format)
            .extent(imageExtent)
            .mipLevels(1)
            .arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(usage or VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT)

        if(device.formatFeatureSupported(a.format, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, optimalTiling = true)) {
            image.usage(image.usage() or VK_IMAGE_USAGE_STORAGE_BIT)
            loadStoreSupported = true
        }

        a.image = VU.getLong("Create VkImage",
            { vkCreateImage(device.vulkanDevice, image, null, this) },
            { image.free(); imageExtent.free() })

        val requirements = VkMemoryRequirements.calloc()
        vkGetImageMemoryRequirements(device.vulkanDevice, a.image, requirements)

        val allocation = VkMemoryAllocateInfo.calloc()
            .allocationSize(requirements.size())
            .memoryTypeIndex(device.getMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT).first())
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .pNext(NULL)

        vkAllocateMemory(device.vulkanDevice, allocation, null, a.memory)
        vkBindImageMemory(device.vulkanDevice, a.image, a.memory.get(0), 0)

        requirements.free()
        allocation.free()

        VU.setImageLayout(
            commandBuffer,
            a.image,
            aspectMask,
            VK_IMAGE_LAYOUT_UNDEFINED,
            if (usage == VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            else imageLayout
        )

        val subresourceRange = VkImageSubresourceRange.calloc()
            .aspectMask(aspectMask)
            .baseMipLevel(0)
            .levelCount(1)
            .baseArrayLayer(0)
            .layerCount(1)

        val iv = VkImageViewCreateInfo.calloc()
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(format)
            .subresourceRange(subresourceRange)
            .image(a.image)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .pNext(NULL)

        vkCreateImageView(device.vulkanDevice, iv, null, a.imageView)
        iv.free()
        subresourceRange.free()

        a.descriptorSetLayout = device.createDescriptorSetLayout(
            descriptorNum = 1,
            descriptorCount = 1,
            type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
        )

        a.descriptorSet = device.createRenderTargetDescriptorSet(
            a.descriptorSetLayout,
            this,
            onlyFor = listOf(a)
        )

        logger.debug("Created sampling DSL ${a.descriptorSetLayout.toHexString()} and DS ${a.descriptorSet.toHexString()} for attachment $name")

        if(loadStoreSupported) {
            val dsl = device.createDescriptorSetLayout(
                descriptorNum = 1,
                descriptorCount = 1,
                type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
            )

            a.loadStoreDescriptorSetLayout = dsl
            a.loadStoreDescriptorSet = device.createRenderTargetDescriptorSet(
                dsl,
                this,
                onlyFor = listOf(a),
                imageLoadStore = true
            )

            logger.debug("Created load/store DSL ${a.loadStoreDescriptorSetLayout?.toHexString()} and DS ${a.loadStoreDescriptorSet?.toHexString()} for attachment $name")
        }

        return a
    }

    /**
     * Internal function to create a depth/stencil attachment of [format], with
     * dimensions [attachmentWidth] x [attachmentHeight].
     */
    private fun createAndAddDepthStencilAttachmentInternal(name: String, format: Int, attachmentWidth: Int, attachmentHeight: Int) {
        val att = createAttachment(format, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, attachmentWidth, attachmentHeight, name)

        val (loadOp, stencilLoadOp) = if (!shouldClear) {
            VK_ATTACHMENT_LOAD_OP_LOAD to VK_ATTACHMENT_LOAD_OP_LOAD
        } else {
            VK_ATTACHMENT_LOAD_OP_CLEAR to VK_ATTACHMENT_LOAD_OP_CLEAR
        }

        val initialImageLayout = if(!shouldClear) {
            VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
        } else {
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        }

        att.desc.samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(loadOp)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(stencilLoadOp)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_STORE)
            .initialLayout(initialImageLayout)
            .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .format(format)

        att.type = VulkanFramebufferType.DEPTH_ATTACHMENT

        attachments.put(name, att)
    }

    /**
     * Internal function to create a new color attachment of format [fornat], with
     * dimensions [attachmentWidth] x [attachmentHeight].
     */
    private fun createAndAddColorAttachmentInternal(name: String, format: Int, attachmentWidth: Int, attachmentHeight: Int) {
        val att = createAttachment(format, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, attachmentWidth, attachmentHeight, name)

        val (loadOp, stencilLoadOp) = if (!shouldClear) {
            VK_ATTACHMENT_LOAD_OP_LOAD to VK_ATTACHMENT_LOAD_OP_LOAD
        } else {
            VK_ATTACHMENT_LOAD_OP_CLEAR to VK_ATTACHMENT_LOAD_OP_DONT_CARE
        }

        val initialImageLayout = if(!shouldClear) {
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        } else {
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        }

        att.desc.samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(loadOp)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(stencilLoadOp)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(initialImageLayout)
            .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .format(format)

        attachments.put(name, att)
    }

    /**
     * Adds a float attachment with a bit depth of [channelDepth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addFloatBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(channelDepth) {
            16 -> VK_FORMAT_R16_SFLOAT
            32 -> VK_FORMAT_R32_SFLOAT
            else -> { logger.warn("Unsupported channel depth $channelDepth, using 16 bit."); VK_FORMAT_R16_SFLOAT }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds a float RG attachment with a bit depth of [channelDepth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addFloatRGBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(channelDepth) {
            16 -> VK_FORMAT_R16G16_SFLOAT
            32 -> VK_FORMAT_R32G32_SFLOAT
            else -> { logger.warn("Unsupported channel depth $channelDepth, using 16 bit."); VK_FORMAT_R16G16_SFLOAT }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds a float RGB attachment with a bit depth of [channelDepth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addFloatRGBBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(channelDepth) {
            16 -> VK_FORMAT_R16G16B16_SFLOAT
            32 -> VK_FORMAT_R32G32B32_SFLOAT
            else -> { logger.warn("Unsupported channel depth $channelDepth, using 16 bit."); VK_FORMAT_R16G16B16A16_SFLOAT }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds a float RGBA attachment with a bit depth of [channelDepth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addFloatRGBABuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(channelDepth) {
            16 -> VK_FORMAT_R16G16B16A16_SFLOAT
            32 -> VK_FORMAT_R32G32B32A32_SFLOAT
            else -> { logger.warn("Unsupported channel depth $channelDepth, using 16 bit."); VK_FORMAT_R16G16B16A16_SFLOAT }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds an unsigned byte RGBA attachment with a bit depth of [channelDepth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addUnsignedByteRGBABuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(channelDepth) {
            8 -> if (sRGB) {
                VK_FORMAT_R8G8B8A8_SRGB
            } else {
                VK_FORMAT_R8G8B8A8_UNORM
            }
            16 -> VK_FORMAT_R16G16B16A16_UNORM
            else -> { logger.warn("Unsupported channel depth $channelDepth, using 16 bit."); VK_FORMAT_R16G16B16A16_UINT }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds an unsigned byte R attachment with a bit depth of [channelDepth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addUnsignedByteRBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(channelDepth) {
            8 -> VK_FORMAT_R8_UNORM
            16 -> VK_FORMAT_R16_UNORM
            else -> { logger.warn("Unsupported channel depth $channelDepth, using 16 bit."); VK_FORMAT_R16_UNORM }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds a depth buffer attachment with a bit depth of [depth], and a size of [attachmentWidth] x [attachmentHeight].
     */
    fun addDepthBuffer(name: String, depth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {
        val format: Int = when(depth) {
            16 -> VK_FORMAT_D16_UNORM
            24 -> VK_FORMAT_D24_UNORM_S8_UINT
            32 -> VK_FORMAT_D32_SFLOAT
            else -> { logger.warn("Unsupported channel depth $depth, using 32 bit."); VK_FORMAT_D32_SFLOAT }
        }

        val bestSupportedFormat = getBestDepthFormat(format).first()

        createAndAddDepthStencilAttachmentInternal(name, bestSupportedFormat, attachmentWidth, attachmentHeight)

        return this
    }

    /**
     * Adds a swapchain-based attachment from the given [swapchain]. The image will be derived
     * from the swapchain's image [index].
     */
    fun addSwapchainAttachment(name: String, swapchain: Swapchain, index: Int): VulkanFramebuffer {
        val att = VulkanFramebufferAttachment()

        att.image = swapchain.images[index]
        att.imageView.put(0, swapchain.imageViews[index])
        att.type = VulkanFramebufferType.COLOR_ATTACHMENT
        att.fromSwapchain = true

        val (loadOp, stencilLoadOp) = if (!shouldClear) {
            VK_ATTACHMENT_LOAD_OP_LOAD to VK_ATTACHMENT_LOAD_OP_LOAD
        } else {
            VK_ATTACHMENT_LOAD_OP_CLEAR to VK_ATTACHMENT_LOAD_OP_DONT_CARE
        }

        val initialImageLayout = VK_IMAGE_LAYOUT_UNDEFINED

        att.desc
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(loadOp)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(stencilLoadOp)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(initialImageLayout)
            .finalLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
            .format(if (sRGB) {
                VK_FORMAT_B8G8R8A8_SRGB
            } else {
                VK_FORMAT_B8G8R8A8_UNORM
            })

        attachments.put(name, att)

        val loadStoreSupported = device.formatFeatureSupported(att.format, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, optimalTiling = true)

        att.descriptorSetLayout = device.createDescriptorSetLayout(
            descriptorNum = 1,
            descriptorCount = 1,
            type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
        )

        att.descriptorSet = device.createRenderTargetDescriptorSet(
            att.descriptorSetLayout,
            this,
            onlyFor = listOf(att)
        )

        logger.debug("Created sampling DSL ${att.descriptorSetLayout.toHexString()} and DS ${att.descriptorSet.toHexString()} for attachment $name")


        if(loadStoreSupported) {
            val dsl = device.createDescriptorSetLayout(
                descriptorNum = 1,
                descriptorCount = 1,
                type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
            )

            att.loadStoreDescriptorSetLayout = dsl
            att.loadStoreDescriptorSet = device.createRenderTargetDescriptorSet(
                dsl,
                this,
                onlyFor = listOf(att),
                imageLoadStore = true
            )

            logger.debug("Created load/store DSL ${att.loadStoreDescriptorSetLayout?.toHexString()} and DS ${att.loadStoreDescriptorSet?.toHexString()} for attachment $name")
        } else {
            logger.debug("Not creating load/store DSL/DS for attachment $name due to lack of feature support for format ${att.desc.format()}")
        }

        return this
    }

    /**
     * Gets a Vulkan attachment description from the current framebuffer state.
     */
    protected fun getAttachmentDescBuffer(): VkAttachmentDescription.Buffer {
        val descriptionBuffer = VkAttachmentDescription.calloc(attachments.size)
        attachments.values.forEach{ descriptionBuffer.put(it.desc) }

        return descriptionBuffer.flip()
    }

    /**
     * Gets all the image views of the current framebuffer.
     */
    protected fun getAttachmentImageViews(): LongBuffer {
        val ivBuffer = memAllocLong(attachments.size)
        attachments.values.forEach{ ivBuffer.put(it.imageView.get(0)) }

        ivBuffer.flip()

        return ivBuffer
    }

    /**
     * Creates the Vulkan Renderpass and Framebuffer from the state of the
     * framebuffer.
     */
    fun createRenderpassAndFramebuffer() {
        val colorDescs = VkAttachmentReference.calloc(attachments.filter { it.value.type == VulkanFramebufferType.COLOR_ATTACHMENT }.size)

        attachments.values.filter { it.type == VulkanFramebufferType.COLOR_ATTACHMENT }.forEachIndexed { i, _ ->
            colorDescs[i]
                .attachment(i)
                .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        }

        val depthDescs: VkAttachmentReference? = if(attachments.any { it.value.type == VulkanFramebufferType.DEPTH_ATTACHMENT }) {
            VkAttachmentReference.calloc()
                .attachment(colorDescs.limit())
                .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        } else {
            null
        }


        logger.trace("Subpass for has ${colorDescs.remaining()} color attachments")

        val subpass = VkSubpassDescription.calloc(1)
            .pColorAttachments(colorDescs)
            .colorAttachmentCount(colorDescs.remaining())
            .pDepthStencilAttachment(depthDescs)
            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .pInputAttachments(null)
            .pPreserveAttachments(null)
            .pResolveAttachments(null)
            .flags(0)

        val dependencyChain = VkSubpassDependency.calloc(2)

//        dependencyChain[0]
//            .srcSubpass(VK_SUBPASS_EXTERNAL)
//            .dstSubpass(0)
//            .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
//            .srcAccessMask(0)
//            .dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
//            .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
//
//        dependencyChain[1]
//            .srcSubpass(0)
//            .dstSubpass(VK_SUBPASS_EXTERNAL)
//            .srcStageMask(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
//            .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_COLOR_ATTACHMENT_READ_BIT)
//            .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
//            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)

        dependencyChain[0]
            .srcSubpass(VK_SUBPASS_EXTERNAL)
            .dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
            .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT or VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
//        .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)

        dependencyChain[1]
            .srcSubpass(0)
            .dstSubpass(VK_SUBPASS_EXTERNAL)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT or VK_PIPELINE_STAGE_TRANSFER_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT or VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT  or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
            .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or VK_ACCESS_SHADER_READ_BIT)
//        .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)

        if(!attachments.any { it.value.fromSwapchain }) {
            dependencyChain[0].dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
            dependencyChain[1].dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
        }

        val attachmentDescs = getAttachmentDescBuffer()
        val renderPassInfo = VkRenderPassCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            .pAttachments(attachmentDescs)
            .pSubpasses(subpass)
            .pDependencies(dependencyChain)
            .pNext(NULL)

        renderPass.put(0, VU.getLong("create renderpass",
            { vkCreateRenderPass(device.vulkanDevice, renderPassInfo, null, this) },
            { attachmentDescs.free() }))

        logger.trace("Created renderpass ${renderPass.get(0)}")

        val attachmentImageViews = getAttachmentImageViews()
        val fbinfo = VkFramebufferCreateInfo.calloc()
            .default()
            .renderPass(renderPass.get(0))
            .pAttachments(attachmentImageViews)
            .width(width)
            .height(height)
            .layers(1)

        framebuffer.put(0, VU.getLong("create framebuffer",
            { vkCreateFramebuffer(device.vulkanDevice, fbinfo, null, this) },
            { fbinfo.free(); memFree(attachmentImageViews); }))

        outputDescriptorSetLayout = device.createDescriptorSetLayout(
            descriptorNum = attachments.count(),
            descriptorCount = 1,
            type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
        )

        outputDescriptorSet = device.createRenderTargetDescriptorSet(
            outputDescriptorSetLayout,
            this
        )

        logger.debug("Created sampling DSL ${outputDescriptorSetLayout.toHexString()} and DS ${outputDescriptorSet.toHexString()} for framebuffer")

        imageLoadStoreDescriptorSetLayout = device.createDescriptorSetLayout(
            descriptorNum = attachments.count { it.value.loadStoreDescriptorSet != null },
            descriptorCount = 1,
            type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
        )

        imageLoadStoreDescriptorSet = device.createRenderTargetDescriptorSet(
            imageLoadStoreDescriptorSetLayout,
            this,
            imageLoadStore = true,
            onlyFor = attachments.values.filter { it.loadStoreDescriptorSet != null }
        )

        logger.debug("Created load/store DSL ${imageLoadStoreDescriptorSetLayout.toHexString()} and DS ${imageLoadStoreDescriptorSet.toHexString()} for framebuffer")


        renderPassInfo.free()
        subpass.free()
        colorDescs.free()
        depthDescs?.free()

        initialized = true
    }

    /**
     * Helper function to set up Vulkan structs.
     */
    fun <T : Struct> T.default(): T {
        if(this is VkSamplerCreateInfo) {
            this.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO).pNext(NULL)
        } else if(this is VkFramebufferCreateInfo) {
            this.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).pNext(NULL)
        }

        return this
    }

    /** Returns a string representation of this framebuffer. */
    override fun toString(): String {
        return "VulkanFramebuffer ${width}x$height (${attachments.size} attachments)"
    }

    /**
     * Returns the best available depth format, from a list of [preferredFormat]s.
     */
    private fun getBestDepthFormat(preferredFormat: Int): List<Int> {
        // this iterates through the list of possible (though not all required formats)
        // and returns the first one that is possible to use as a depth buffer on the
        // given physical device.
        val props = VkFormatProperties.calloc()
        val format = intArrayOf(
            preferredFormat,
            VK_FORMAT_D32_SFLOAT,
            VK_FORMAT_D32_SFLOAT_S8_UINT,
            VK_FORMAT_D24_UNORM_S8_UINT,
            VK_FORMAT_D16_UNORM_S8_UINT,
            VK_FORMAT_D16_UNORM
        ).filter {
            vkGetPhysicalDeviceFormatProperties(device.physicalDevice, it, props)

            props.optimalTilingFeatures() and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT > 0
        }

        logger.debug("Using $format as depth format.")

        props.free()
        return format
    }

    /** Returns the number of current color attachments. */
    fun colorAttachmentCount() = attachments.count { it.value.type == VulkanFramebufferType.COLOR_ATTACHMENT }

    /** Returns the number of current depth attachments. */
    fun depthAttachmentCount() = attachments.count { it.value.type == VulkanFramebufferType.DEPTH_ATTACHMENT }

    /** Closes this framebuffer instance, releasing all of its resources. */
    override fun close() {
        if(initialized) {
            attachments.values.forEach { it.close() }

            device.removeDescriptorSetLayout(outputDescriptorSetLayout)
            device.removeDescriptorSetLayout(imageLoadStoreDescriptorSetLayout)

            vkDestroyRenderPass(device.vulkanDevice, renderPass.get(0), null)
            memFree(renderPass)

            vkDestroySampler(device.vulkanDevice, framebufferSampler.get(0), null)
            memFree(framebufferSampler)

            vkDestroyFramebuffer(device.vulkanDevice, this.framebuffer.get(0), null)
            memFree(framebuffer)

            initialized = false
        }
    }
}

