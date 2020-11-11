package graphics.scenery.controls

import org.joml.Matrix4f
import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Display
import graphics.scenery.backends.vulkan.*
import graphics.scenery.mesh.Mesh
import graphics.scenery.utils.LazyLogger
import kotlinx.coroutines.*
import org.joml.Quaternionf
import org.joml.Vector2i
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAllocInt
import org.lwjgl.system.MemoryUtil.memAllocLong
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.NVDedicatedAllocation.*
import org.lwjgl.vulkan.NVExternalMemory.*
import org.lwjgl.vulkan.NVExternalMemoryCapabilities.*
import org.lwjgl.vulkan.NVExternalMemoryWin32.*
import org.lwjgl.vulkan.NVWin32KeyedMutex.VK_STRUCTURE_TYPE_WIN32_KEYED_MUTEX_ACQUIRE_RELEASE_INFO_NV
import org.lwjgl.vulkan.VK10.*
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMsg
import org.zeromq.ZPoller
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.PI

/**
 * Hololens HMD class
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @constructor Creates a new Hololens HMD instance
 */
class Hololens: TrackerInput, Display, Hubable {
    override var hub: Hub? = null

    private val logger by LazyLogger()

    private val identityQuat = Quaternionf()
    private val nullVector = Vector3f(0.0f)

    private val hololensDisplaySize = Vector2i(1280, 720)
    private val headToEyeTransforms = arrayOf(
        Matrix4f().identity().translate(-0.033f, 0.0f, 0.0f),
        Matrix4f().identity().translate(0.033f, 0.0f, 0.0f))
    // BGR is native surface format and saves unnecessary conversions
    private val textureFormat = VK_FORMAT_B8G8R8A8_SRGB

    private val defaultPort = 1339
    private val zmqContext = ZContext(4)
    private val zmqSocket = zmqContext.createSocket(ZMQ.REQ)
    private val subscriberSockets = HashMap<String, Job>()

    data class CommandBufferWithStatus(val commandBuffer: VulkanCommandBuffer, var current: Boolean = false)
    private var commandBuffers: MutableList<CommandBufferWithStatus> = mutableListOf()
    private var hololensCommandPool = -1L
    private var d3dImages: List<Pair<VulkanTexture.VulkanImage, Long>?> = emptyList()
    private var currentImageIndex: Int = 0

    private val acqKeys = memAllocLong(1).put(0, 0)
    private val releaseKeys = memAllocLong(1).put(0, 0)
    private val memoryHandleBuffer = memAllocLong(1)
    private val acquireTimeout = memAllocInt(1).put(0, 1)

    private var leftProjection: Matrix4f? = null
    private var rightProjection: Matrix4f? = null

    private var poseLeftDeque = ArrayDeque<Matrix4f>(3)
    private var poseRightDeque = ArrayDeque<Matrix4f>(3)
    private var poseLeft: Matrix4f = Matrix4f().identity()
    private var poseRight: Matrix4f = Matrix4f().identity()

    override var events = TrackerInputEventHandlers()

    init {
        zmqSocket.connect("tcp://localhost:$defaultPort")
    }

    /**
     * Returns the orientation of the HMD
     *
     * @returns Matrix4f with orientation
     */
    override fun getOrientation(): Quaternionf {
        // TODO: Return actual Hololens orientation
        return identityQuat
    }

    /**
     * Returns the orientation of the given device, or a unit quaternion if the device is not found.
     *
     * @returns Matrix4f with orientation
     */
    override fun getOrientation(id: String): Quaternionf {
        // TODO: Return actual Hololens orientation
        return identityQuat
    }

    /**
     * Returns the absolute position as Vector3f
     *
     * @return HMD position as Vector3f
     */
    override fun getPosition(): Vector3f {
        // TODO: Return actual Hololens position
        return nullVector
    }

    /**
     * Returns the HMD pose
     *
     * @return HMD pose as Matrix4f
     */
    override fun getPose(): Matrix4f {
        // TODO: Return actual Hololens pose
        return poseLeft
    }

    /**
     * Check whether the HMD is initialized and working
     *
     * @return True if HMD is initialiased correctly and working properly
     */
    override fun initializedAndWorking(): Boolean {
        // TODO: Return whether Hololens is actually connected and working
        return true
    }

    /**
     * update state
     */
    override fun update() {
        if(poseLeftDeque.isNotEmpty() && poseRightDeque.isNotEmpty()) {
            poseLeft = poseLeftDeque.pop()
            poseRight = poseRightDeque.pop()
        }
    }

    override fun getWorkingTracker(): TrackerInput? {
        return this
    }

    /**
     * Returns the per-eye projection matrix
     *
     * @param[eye] The index of the eye
     * @return Matrix4f containing the per-eye projection matrix
     */
    override fun getEyeProjection(eye: Int, nearPlane: Float, farPlane: Float): Matrix4f {
        return when(eye) {
            0 -> leftProjection ?: Matrix4f().perspective(50.0f * PI.toFloat()/180.0f, 1.0f, nearPlane, farPlane)
            1 -> rightProjection ?: Matrix4f().perspective(50.0f * PI.toFloat()/180.0f, 1.0f, nearPlane, farPlane)
            else -> { logger.error("3rd eye, wtf?"); Matrix4f().identity() }
        }
    }

    override fun getPoseForEye(eye: Int): Matrix4f {
        return when(eye) {
            0 -> poseLeft
            else -> poseRight
        }
    }

    /**
     * Returns a list of poses for the devices [type] given.
     *
     * @return Pose as Matrix4f
     */
    override fun getPose(type: TrackedDeviceType): List<TrackedDevice> {
        // TODO: Improve this
        return emptyList()
    }

    /**
     * Returns the inter-pupillary distance (IPD)
     *
     * @return IPD as Float
     */
    override fun getIPD(): Float {
        return 0.05f
    }

    /**
     * Query the HMD whether a compositor is used or the renderer should take
     * care of displaying on the HMD on its own. For the Hololens, this is always true as it is not addressed
     * as regular display.
     *
     * @return Always true, Hololens requires compositor use.
     */
    override fun hasCompositor(): Boolean {
        return true
    }

    override fun loadModelForMesh(type: TrackedDeviceType, mesh: Mesh): Mesh {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun loadModelForMesh(device: TrackedDevice, mesh: Mesh): Mesh {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun attachToNode(device: TrackedDevice, node: Node, camera: Camera?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Submit OpenGL texture IDs to the compositor. The texture is assumed to have the left eye in the
     * left half, right eye in the right half.
     *
     * @param[textureId] OpenGL Texture ID of the left eye texture
     */
    override fun submitToCompositor(textureId: Int) {
        logger.error("Hololens is not supported with OpenGL rendering") //To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Creates a [VulkanTexture.VulkanImage] backed by a shared Direct3D handle.
     *
     * @param[sharedHandleAddress] The address of the shared handle.
     * @param[width] Width of the texture to be created.
     * @param[height] Height of the texture to be created.
     * @param[format] Image format for the texture.
     * @param[device] A [VulkanDevice] handle.
     * @param[queue] The Vulkan command queue to use.
     * @param[commandPool] The Vulkan command pool to use.
     */
    private fun getSharedHandleVulkanTexture(sharedHandleAddress: Long, width: Int, height: Int, format: Int, device: VulkanDevice, queue: VkQueue, commandPool: Long): Pair<VulkanTexture.VulkanImage, Long>? {
        logger.info("Registered D3D shared texture handle as ${sharedHandleAddress.toHexString()}/${sharedHandleAddress.toString(16)}")

        // VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_IMAGE_BIT_NV does not seem to work here
        val handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_IMAGE_BIT_NV
        val extProperties = VkExternalImageFormatPropertiesNV.calloc()

        val formatSupported = vkGetPhysicalDeviceExternalImageFormatPropertiesNV(
            device.physicalDevice,
            textureFormat,
            VK_IMAGE_TYPE_2D,
            VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
            0,
            handleType,
            extProperties)

        if (formatSupported == VK_ERROR_FORMAT_NOT_SUPPORTED) {
            logger.error("Shared handles not supported, omfg!")
            return null
        }

        if(formatSupported < 0) {
            logger.error("Something else went wrong: $formatSupported")
        }

        logger.debug("Can import these types: ")
        logger.debug(" VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_IMAGE_BIT_NV: ${extProperties.compatibleHandleTypes() and VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_IMAGE_BIT_NV != 0}")
        logger.debug(" VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_IMAGE_KMT_BIT_NV: ${extProperties.compatibleHandleTypes() and VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_IMAGE_KMT_BIT_NV != 0}")
        logger.debug(" VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_NV : ${extProperties.compatibleHandleTypes() and VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_NV != 0}")
        logger.debug(" VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_KMT_BIT_NV : ${extProperties.compatibleHandleTypes() and VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_KMT_BIT_NV != 0}")

        if (extProperties.externalMemoryFeatures() and VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT_NV == 0) {
            logger.error("Importable handles are not support, omfg! ${extProperties.externalMemoryFeatures()}")
            return null
        }

        if(handleType and extProperties.compatibleHandleTypes() == 0) {
            logger.error("Requested import type not available! ${extProperties.compatibleHandleTypes()}")
            return null
        }

        val extMemoryImageInfo = VkExternalMemoryImageCreateInfoNV.calloc()
            .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO_NV)
            .pNext(0)
            .handleTypes(handleType)

        val dedicatedAllocationCreateInfo = VkDedicatedAllocationImageCreateInfoNV.calloc()
            .sType(VK_STRUCTURE_TYPE_DEDICATED_ALLOCATION_IMAGE_CREATE_INFO_NV)
            .pNext(0)
            .dedicatedAllocation(false)

        if (extProperties.externalMemoryFeatures() and VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT_NV != 0) {
            logger.info("Platform requires dedicated allocation")

            extMemoryImageInfo.pNext(dedicatedAllocationCreateInfo.address())
            dedicatedAllocationCreateInfo.dedicatedAllocation(true)
        }

        val t = VulkanTexture(device, VulkanRenderer.CommandPools(commandPool, commandPool, commandPool, commandPool), queue, queue,
            width, height, 1,
            format, 1, true, true)

        val imageCreateInfo = VkImageCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .pNext(extMemoryImageInfo.address())
            .imageType(VK_IMAGE_TYPE_2D)
            .format(format)
            .mipLevels(1)
            .arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            .flags(0)

        imageCreateInfo.extent().set(hololensDisplaySize.x().toInt(), hololensDisplaySize.y().toInt(), 1)

        var memoryHandle: Long = -1L
        val img = t.createImage(hololensDisplaySize.x().toInt(), hololensDisplaySize.y().toInt(), 1,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT,
            VK_IMAGE_TILING_OPTIMAL, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 1,
            imageCreateInfo = imageCreateInfo,
            customAllocator = { memoryRequirements, allocatedImage ->
                logger.debug("Using custom image allocation for external handle ...")
                val memoryTypeIndex = device.getMemoryType(memoryRequirements.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)

                if(memoryTypeIndex.isEmpty()) {
                    logger.error("Could not find suitable memory type")
                } else {
                    logger.debug("Got memory types ${memoryTypeIndex.joinToString(", ")}")
                }

                val importMemoryInfo = VkImportMemoryWin32HandleInfoNV.calloc()
                    .sType(VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_NV)
                    .pNext(0)
                    .handleType(handleType)
                    .handle(sharedHandleAddress)

                val memoryInfo = VkMemoryAllocateInfo.calloc()
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .pNext(importMemoryInfo.address())
                    .allocationSize(memoryRequirements.size())
                    .memoryTypeIndex(memoryTypeIndex.first())

                val dedicatedAllocationInfo = VkDedicatedAllocationMemoryAllocateInfoNV.calloc()
                    .sType(VK_STRUCTURE_TYPE_DEDICATED_ALLOCATION_MEMORY_ALLOCATE_INFO_NV)
                    .pNext(0)

                if (extProperties.externalMemoryFeatures() and VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT_NV != 0) {
                    logger.debug("Using VK_NV_dedicated_allocation")
                    dedicatedAllocationInfo.image(allocatedImage)
                    importMemoryInfo.pNext(dedicatedAllocationInfo.address())
                }

                logger.debug("Trying to allocate ${memoryRequirements.size()} bytes for shared texture")

                memoryHandle = VU.getLong("Allocate memory for D3D shared image",
                    { vkAllocateMemory(device.vulkanDevice, memoryInfo, null, this) },
                    { dedicatedAllocationInfo.free(); memoryInfo.free(); importMemoryInfo.free(); })
                memoryHandle
            })

        with(VU.newCommandBuffer(device, commandPool, autostart = true)) {
            VulkanTexture.transitionLayout(img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 1,
                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                commandBuffer = this)

            this
        }.endCommandBuffer(device, commandPool,
            queue, flush = true, dealloc = true)

        return img to memoryHandle
    }

    /**
     * Submit a Vulkan texture handle to the compositor
     *
     * @param[width] Texture width
     * @param[height] Texture height
     * @param[format] Vulkan texture format
     * @param[instance] Vulkan Instance
     * @param[device] Vulkan device
     * @param[queue] Vulkan queue
     * @param[image] The Vulkan texture image to be presented to the compositor
     */
    override fun submitToCompositorVulkan(width: Int, height: Int, format: Int, instance: VkInstance, device: VulkanDevice, queue: VkQueue, image: Long) {
        if(hololensCommandPool == -1L) {
            hololensCommandPool = device.createCommandPool(device.queues.graphicsQueue.first)
        }

        if(leftProjection == null) {
            zmqSocket.send("LeftPR")
            val matrixData = zmqSocket.recv()
            assert(matrixData.size == 64)

            leftProjection = Matrix4f(ByteBuffer.wrap(matrixData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer())
            logger.info("Hololens left projection: $leftProjection")
        }

        if(rightProjection == null) {
            zmqSocket.send("RightPR")
            val matrixData = zmqSocket.recv()
            assert(matrixData.size == 64)

            rightProjection = Matrix4f(ByteBuffer.wrap(matrixData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer())
            logger.info("Hololens right projection: $rightProjection")
        }

        if(d3dImages.isEmpty()) {
            logger.info("Trying to register shared handles")
            zmqSocket.send("RegPid${SceneryBase.getProcessID()}/3")
            val reply = zmqSocket.recvStr()

            if(reply.startsWith("NotReady")) {
                return
            }

            logger.info("Received handles: $reply")
            val handles = reply.split("/").filter { it.isNotEmpty() }.map { BigInteger(it, 16).toLong() }

            d3dImages = handles.mapNotNull { handle ->
                getSharedHandleVulkanTexture(handle,
                        hololensDisplaySize.x().toInt(),
                        hololensDisplaySize.y().toInt(),
                        textureFormat,
                        device,
                        queue,
                        hololensCommandPool)
            }

            commandBuffers = d3dImages.map { CommandBufferWithStatus(VulkanCommandBuffer(device, null, false), false) }.toMutableList()

            logger.info("Registered ${d3dImages.size} shared handles")

            subscribe("transforms.ViewTransforms")

            if(d3dImages.isEmpty() || commandBuffers.size == 0) {
                logger.error("Did not get any Vulkan render targets back!")
                return
            }
        }

        // return if we can't get a current image
        val currentImage = d3dImages[currentImageIndex] ?: return
        var currentCommandBuffer = commandBuffers[currentImageIndex]

        // blit into D3D image
        if(!currentCommandBuffer.current) {
            logger.info("Recording command buffer for image index $currentImageIndex")
            currentCommandBuffer = commandBuffers[currentImageIndex]

            currentCommandBuffer.commandBuffer.commandBuffer = with(VU.newCommandBuffer(device, hololensCommandPool, autostart = true)) {
                MemoryStack.stackPush().use { stack ->
                    logger.info("Blitting image of size ${width}x$height")
                    val imageBlit = VkImageBlit.callocStack(1, stack)
                    val type = VK_IMAGE_ASPECT_COLOR_BIT

                    imageBlit.srcSubresource().set(type, 0, 0, 1)
                    imageBlit.srcOffsets(0).set(0, 0, 0)
                    imageBlit.srcOffsets(1).set(width, height, 1)

                    imageBlit.dstSubresource().set(type, 0, 0, 1)
                    imageBlit.dstOffsets(0).set(0, 0, 0)
                    imageBlit.dstOffsets(1).set(hololensDisplaySize.x().toInt(), hololensDisplaySize.y().toInt(), 1)

                    val subresourceRange = VkImageSubresourceRange.callocStack(stack)
                        .aspectMask(type)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)

                    // transition source attachment
                    VulkanTexture.transitionLayout(image,
                        KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        subresourceRange = subresourceRange,
                        commandBuffer = this,
                        srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                    )

                    // transition destination attachment
                    VulkanTexture.transitionLayout(currentImage.first.image,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        subresourceRange = subresourceRange,
                        commandBuffer = this,
                        srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                    )

                    vkCmdBlitImage(this@with,
                        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        currentImage.first.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        imageBlit, VK_FILTER_NEAREST
                    )

                    // transition destination attachment back to attachment
                    VulkanTexture.transitionLayout(currentImage.first.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        subresourceRange = subresourceRange,
                        commandBuffer = this,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                    )

                    // transition source attachment back to shader read-only
                    VulkanTexture.transitionLayout(image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        subresourceRange = subresourceRange,
                        commandBuffer = this,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                    )
                }

                this
            }

            currentCommandBuffer.commandBuffer.commandBuffer?.endCommandBuffer(device, hololensCommandPool, queue,
                flush = false, dealloc = false, submitInfoPNext = null)
            currentCommandBuffer.current = true
        }

        memoryHandleBuffer.put(0, currentImage.second)

        val keyedMutex = VkWin32KeyedMutexAcquireReleaseInfoNV.calloc()
            .sType(VK_STRUCTURE_TYPE_WIN32_KEYED_MUTEX_ACQUIRE_RELEASE_INFO_NV)
            .pNext(0)
            .acquireCount(1)
            .pAcquireSyncs(memoryHandleBuffer)
            .pAcquireKeys(acqKeys)
            .pAcquireTimeoutMilliseconds(acquireTimeout)
            .releaseCount(1)
            .pReleaseKeys(releaseKeys)
            .pReleaseSyncs(memoryHandleBuffer)

        currentCommandBuffer.commandBuffer.commandBuffer?.submit(queue, submitInfoPNext = keyedMutex)
        currentImageIndex = (currentImageIndex+1) % d3dImages.size
    }

    /**
     * Returns the optimal render target size for the HMD as 2D vector
     *
     * @return Render target size as 2D vector
     */
    override fun getRenderTargetSize(): Vector2i {
        return hololensDisplaySize
    }

    override fun getVulkanInstanceExtensions(): List<String> {
        return listOf("VK_NV_external_memory_capabilities",
            "VK_KHR_external_memory_capabilities",
            "VK_KHR_get_physical_device_properties2")
    }

    override fun getVulkanDeviceExtensions(physicalDevice: VkPhysicalDevice): List<String> {
        return listOf("VK_NV_dedicated_allocation",
            "VK_NV_external_memory",
            "VK_NV_external_memory_win32",
            "VK_NV_win32_keyed_mutex")
    }

    override fun getWorkingDisplay(): Display? {
        return this
    }

    /**
     * Returns the per-eye transform that moves from head to eye
     *
     * @param[eye] The eye index
     * @return Matrix4f containing the transform
     */
    override fun getHeadToEyeTransform(eye: Int): Matrix4f {
        return headToEyeTransforms[eye]
    }

    override fun getTrackedDevices(ofType: TrackedDeviceType): Map<String, TrackedDevice> {
        TODO("Not implemented yet")
    }

    private fun subscribe(topic: String) {
        if(!subscriberSockets.containsKey(topic)) {

            val job = GlobalScope.launch {
                val socket = zmqContext.createSocket(ZMQ.SUB)
                val poller = ZPoller(zmqContext)
                poller.register(socket, ZMQ.Poller.POLLIN)

                try {
                    socket.connect("tcp://localhost:${defaultPort+1}")
                    socket.subscribe(topic)
                    logger.info("Subscribed to topic $topic")

                    while (isActive) {
                        poller.poll(1)

                        if(poller.isReadable(socket)) {
                            val msg = ZMsg.recvMsg(socket)
                            val msgType = msg.popString()

                            when(msgType) {
                                "transforms.ViewTransforms" -> {
                                    val matrixData = msg.pop().data
                                    assert(matrixData.size == 128)

                                    val b0 = ByteBuffer.wrap(matrixData).order(ByteOrder.LITTLE_ENDIAN).limit(16 * 4) as ByteBuffer
                                    val left = Matrix4f(b0.asFloatBuffer())

                                    val b1 = (b0.position(16 * 4).limit(16 * 8) as ByteBuffer).asFloatBuffer()
                                    val right = Matrix4f(b1)

                                    poseLeftDeque.push(left)
                                    poseRightDeque.push(right)
                                }
                            }

                            msg.destroy()
                        }
                    }
                } finally {
                    logger.debug("Closing topic socket for $topic")
                    poller.unregister(socket)
                    poller.close()
                    socket.close()
                }
            }

            subscriberSockets[topic] = job
        }
    }
}
