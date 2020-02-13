package graphics.scenery.backends.vulkan

import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memUTF8
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.util.*
import kotlin.collections.ArrayList

/**
 * Describes a Vulkan device attached to an [instance] and a [physicalDevice].
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
open class VulkanDevice(val instance: VkInstance, val physicalDevice: VkPhysicalDevice, val deviceData: DeviceData, extensionsQuery: (VkPhysicalDevice) -> Array<String> = { arrayOf() }, validationLayers: Array<String> = arrayOf(), headless: Boolean = false) {

    protected val logger by LazyLogger()
    /** Stores available memory types on the device. */
    val memoryProperties: VkPhysicalDeviceMemoryProperties
    /** Stores the Vulkan-internal device. */
    val vulkanDevice: VkDevice
    /** Stores available queue indices. */
    val queueIndices: QueueIndices
    /** Stores available extensions */
    val extensions = ArrayList<String>()

    /**
     * Enum class for GPU device types. Can be unknown, other, integrated, discrete, virtual or CPU.
     */
    enum class DeviceType { Unknown, Other, IntegratedGPU, DiscreteGPU, VirtualGPU, CPU }

    /**
     * Class to store device-specific metadata.
     *
     * @property[vendor] The vendor name of the device.
     * @property[name] The name of the device.
     * @property[driverVersion] The driver version as represented as string.
     * @property[apiVersion] The Vulkan API version supported by the device, represented as string.
     * @property[type] The [DeviceType] of the GPU.
     */
    data class DeviceData(val vendor: String, val name: String, val driverVersion: String, val apiVersion: String, val type: DeviceType) {
        fun toFullString() = "$vendor $name ($type, driver version $driverVersion, Vulkan API $apiVersion)"
    }

    /**
     * Data class to store device-specific queue indices.
     *
     * @property[presentQueue] The index of the present queue
     * @property[graphicsQueue] The index of the graphics queue
     * @property[computeQueue] The index of the compute queue
     */
    data class QueueIndices(val presentQueue: Int, val transferQueue: Int, val graphicsQueue: Int, val computeQueue: Int)

    init {
        val result = stackPush().use { stack ->
            val pQueueFamilyPropertyCount = stack.callocInt(1)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null)
            val queueCount = pQueueFamilyPropertyCount.get(0)
            val queueProps = VkQueueFamilyProperties.callocStack(queueCount, stack)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, queueProps)

            var graphicsQueueFamilyIndex = 0
            var transferQueueFamilyIndex = 0
            var computeQueueFamilyIndex = 0
            val presentQueueFamilyIndex = 0
            var index = 0

            while (index < queueCount) {
                if (queueProps.get(index).queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) {
                    graphicsQueueFamilyIndex = index
                }

                if (queueProps.get(index).queueFlags() and VK_QUEUE_TRANSFER_BIT != 0) {
                    transferQueueFamilyIndex = index
                }

                if (queueProps.get(index).queueFlags() and VK_QUEUE_COMPUTE_BIT != 0) {
                    computeQueueFamilyIndex = index
                }

                index++
            }

            val requiredFamilies = listOf(
                graphicsQueueFamilyIndex,
                transferQueueFamilyIndex,
                computeQueueFamilyIndex)
                .groupBy { it }

            logger.info("Creating ${requiredFamilies.size} distinct queue groups")

            /**
             * Adjusts the queue count of a [VkDeviceQueueCreateInfo] struct to [num].
             */
            fun VkDeviceQueueCreateInfo.queueCount(num: Int): VkDeviceQueueCreateInfo {
                VkDeviceQueueCreateInfo.nqueueCount(this.address(), num)
                return this
            }

            val queueCreateInfo = VkDeviceQueueCreateInfo.callocStack(requiredFamilies.size, stack)

            requiredFamilies.entries.forEachIndexed { i, (familyIndex, group) ->
                val size = minOf(queueProps.get(familyIndex).queueCount(), group.size)
                logger.debug("Adding queue with familyIndex=$familyIndex, size=$size")

                val pQueuePriorities = stack.callocFloat(group.size)
                for(pr in 0 until group.size) { pQueuePriorities.put(pr, 1.0f) }

                queueCreateInfo[i]
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(familyIndex)
                    .pQueuePriorities(pQueuePriorities)
                    .queueCount(size)
            }

            val extensionsRequested = extensionsQuery.invoke(physicalDevice)
            logger.debug("Requested extensions: ${extensionsRequested.joinToString(", ")} ${extensionsRequested.size}")
            val utf8Exts = extensionsRequested.map { stack.UTF8(it) }

            // allocate enough pointers for required extensions, plus the swapchain extension
            // if we are not running in headless mode
            val extensions = if(!headless) {
                val e = stack.callocPointer(1 + extensionsRequested.size)
                e.put(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                e
            } else {
                stack.callocPointer(extensionsRequested.size)
            }

            utf8Exts.forEach { extensions.put(it) }
            extensions.flip()

            if(validationLayers.isNotEmpty()) {
                logger.warn("Enabled Vulkan API validations. Expect degraded performance.")
            }

            val ppEnabledLayerNames = stack.callocPointer(validationLayers.size)
            var i = 0
            while (i < validationLayers.size) {
                ppEnabledLayerNames.put(memUTF8(validationLayers[i]))
                i++
            }
            ppEnabledLayerNames.flip()

            val enabledFeatures = VkPhysicalDeviceFeatures.callocStack(stack)
                .samplerAnisotropy(true)
                .largePoints(true)
                .geometryShader(true)

            val deviceCreateInfo = VkDeviceCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(MemoryUtil.NULL)
                .pQueueCreateInfos(queueCreateInfo)
                .ppEnabledExtensionNames(extensions)
                .ppEnabledLayerNames(ppEnabledLayerNames)
                .pEnabledFeatures(enabledFeatures)

            logger.debug("Creating device...")
            val pDevice = stack.callocPointer(1)
            val err = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice)
            val device = pDevice.get(0)

            if (err != VK_SUCCESS) {
                throw IllegalStateException("Failed to create device: " + VU.translate(err))
            }
            logger.debug("Device successfully created.")

            val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc()
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties)

            VulkanRenderer.DeviceAndGraphicsQueueFamily(VkDevice(device, physicalDevice, deviceCreateInfo),
                graphicsQueueFamilyIndex, computeQueueFamilyIndex, presentQueueFamilyIndex, transferQueueFamilyIndex, memoryProperties)

            Triple(VkDevice(device, physicalDevice, deviceCreateInfo),
                QueueIndices(
                    presentQueue = presentQueueFamilyIndex,
                    transferQueue = transferQueueFamilyIndex,
                    computeQueue = computeQueueFamilyIndex,
                    graphicsQueue = graphicsQueueFamilyIndex),
                memoryProperties
            )
        }

        vulkanDevice = result.first
        queueIndices = result.second
        memoryProperties = result.third

        extensions.addAll(extensionsQuery.invoke(physicalDevice))
        extensions.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)

        logger.debug("Created logical Vulkan device on ${deviceData.vendor} ${deviceData.name}")
    }

    /**
     * Returns the available memory types on this devices that
     * bear [typeBits] and [flags]. May return an empty list in case
     * the device does not support the given types and flags.
     */
    fun getMemoryType(typeBits: Int, flags: Int): List<Int> {
        var bits = typeBits
        val types = ArrayList<Int>(5)

        for (i in 0 until memoryProperties.memoryTypeCount()) {
            if (bits and 1 == 1) {
                if ((memoryProperties.memoryTypes(i).propertyFlags() and flags) == flags) {
                    types.add(i)
                }
            }

            bits = bits shr 1
        }

        if(types.isEmpty()) {
            logger.warn("Memory type $flags not found for device $this (${vulkanDevice.address().toHexString()}")
        }

        return types
    }

    /**
     * Creates a command pool with a given [queueNodeIndex] for this device.
     */
    fun createCommandPool(queueNodeIndex: Int): Long {
        return stackPush().use { stack ->
            val cmdPoolInfo = VkCommandPoolCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(queueNodeIndex)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)

            val pCmdPool = stack.callocLong(1)
            val err = vkCreateCommandPool(vulkanDevice, cmdPoolInfo, null, pCmdPool)
            val commandPool = pCmdPool.get(0)

            if (err != VK_SUCCESS) {
                throw IllegalStateException("Failed to create command pool: " + VU.translate(err))
            }

            logger.debug("Created command pool ${commandPool.toHexString()}")
            commandPool
        }
    }

    /**
     * Destroys the command pool given by [commandPool].
     */
    fun destroyCommandPool(commandPool: Long) {
        vkDestroyCommandPool(vulkanDevice, commandPool, null)
    }

    /**
     * Returns a string representation of this device.
     */
    override fun toString(): String {
        return "${deviceData.vendor} ${deviceData.name}"
    }

    /**
     * Destroys this device, waiting for all operations to finish before.
     */
    fun close() {
        logger.debug("Closing device ${deviceData.vendor} ${deviceData.name}...")
        vkDeviceWaitIdle(vulkanDevice)
        vkDestroyDevice(vulkanDevice, null)
        logger.debug("Device closed.")

        memoryProperties.free()
    }

    /**
     * Utility functions for [VulkanDevice].
     */
    companion object {
        val logger by LazyLogger()

        /**
         * Data class for defining device/driver-specific workarounds.
         *
         * @property[filter] A lambda to define the condition to trigger this workaround, must return a boolean.
         * @property[description] A string description of the cause and effects of the workaround
         * @property[workaround] A lambda that will be executed if this [DeviceWorkaround] is triggered.
         */
        data class DeviceWorkaround(val filter: (DeviceData) -> Boolean, val description: String, val workaround: (DeviceData) -> Any)


        val deviceWorkarounds: List<DeviceWorkaround> = listOf(
//            DeviceWorkaround(
//                { it.vendor == "Nvidia" && it.driverVersion.substringBefore(".").toInt() >= 396 },
//                "Nvidia 396.xx series drivers are unsupported due to crashing bugs in the driver") {
//                if(System.getenv("__GL_NextGenCompiler") == null) {
//                    logger.warn("The graphics driver version you are using (${it.driverVersion}) contains a bug that prevents scenery's Vulkan renderer from functioning correctly.")
//                    logger.warn("Please set the environment variable __GL_NextGenCompiler to 0 and restart the application to work around this issue.")
//                    logger.warn("For this session, scenery will fall back to the OpenGL renderer in 20 seconds.")
//                    Thread.sleep(20000)
//
//                    throw RuntimeException("Bug in graphics driver, falling back to OpenGL")
//                }
//            }
        )

        private fun toDeviceType(vkDeviceType: Int): DeviceType {
            return when(vkDeviceType) {
                0 -> DeviceType.Other
                1 -> DeviceType.IntegratedGPU
                2 -> DeviceType.DiscreteGPU
                3 -> DeviceType.VirtualGPU
                4 -> DeviceType.CPU
                else -> DeviceType.Unknown
            }
        }

        private fun vendorToString(vendor: Int): String =
            when(vendor) {
                0x1002 -> "AMD"
                0x10DE -> "Nvidia"
                0x8086 -> "Intel"
                else -> "(Unknown vendor)"
            }

        private fun decodeDriverVersion(version: Int) =
            Triple(
                version and 0xFFC00000.toInt() shr 22,
                version and 0x003FF000 shr 12,
                version and 0x00000FFF
            )

        private fun driverVersionToString(version: Int) =
            decodeDriverVersion(version).toList().joinToString(".")

        /**
         * Creates a [VulkanDevice] in a given [instance] from a physical device, requesting extensions
         * given by [additionalExtensions]. The device selection is done in a fuzzy way by [physicalDeviceFilter],
         * such that one can filter for certain vendors, e.g.
         */
        @JvmStatic fun fromPhysicalDevice(instance: VkInstance, physicalDeviceFilter: (Int, DeviceData) -> Boolean,
                                          additionalExtensions: (VkPhysicalDevice) -> Array<String> = { arrayOf() },
                                          validationLayers: Array<String> = arrayOf(),
                                          discoveredDevices: ArrayList<String>,
                                          headless: Boolean = false): VulkanDevice {
            return stackPush().use { stack ->

                val physicalDeviceCount = VU.getInt("Enumerate physical devices") {
                    vkEnumeratePhysicalDevices(instance, this, null)
                }

                if (physicalDeviceCount < 1) {
                    throw IllegalStateException("No Vulkan-compatible devices found!")
                }

                val physicalDevices = VU.getPointers("Getting Vulkan physical devices", physicalDeviceCount) {
                    vkEnumeratePhysicalDevices(instance, intArrayOf(physicalDeviceCount), this)
                }

                var devicePreference = 0

                logger.info("Physical devices: ")
                val properties: VkPhysicalDeviceProperties = VkPhysicalDeviceProperties.callocStack(stack)
                val deviceList = ArrayList<DeviceData>(10)

                for (i in 0 until physicalDeviceCount) {
                    val device = VkPhysicalDevice(physicalDevices.get(i), instance)

                    vkGetPhysicalDeviceProperties(device, properties)

                    val deviceData = DeviceData(
                        vendor = vendorToString(properties.vendorID()),
                        name = properties.deviceNameString(),
                        driverVersion = driverVersionToString(properties.driverVersion()),
                        apiVersion = driverVersionToString(properties.apiVersion()),
                        type = toDeviceType(properties.deviceType()))

                    if(physicalDeviceFilter.invoke(i, deviceData)) {
                        logger.debug("Device filter matches device $i, $deviceData")
                        devicePreference = i
                    }

                    deviceList.add(deviceData)
                    discoveredDevices.add("Vulkan: "+deviceData.name)
                }

                deviceList.forEachIndexed { i, device ->
                    val selected = if (devicePreference == i) {
                        "(selected)"
                    } else {
                        ""
                    }

                    logger.info("  $i: ${device.toFullString()} $selected")
                }

                val selectedDevice = physicalDevices.get(devicePreference)
                val selectedDeviceData = deviceList[devicePreference]

                if(System.getProperty("scenery.DisableDeviceWorkarounds", "false")?.toBoolean() != true) {
                    deviceWorkarounds.forEach {
                        if (it.filter.invoke(selectedDeviceData)) {
                            logger.warn("Workaround activated: ${it.description}")
                            it.workaround.invoke(selectedDeviceData)
                        }
                    }
                } else {
                    logger.warn("Device-specific workarounds disabled upon request, expect weird things to happen.")
                }

                val physicalDevice = VkPhysicalDevice(selectedDevice, instance)

                physicalDevices.free()

                VulkanDevice(instance, physicalDevice, selectedDeviceData, additionalExtensions, validationLayers, headless)
            }
        }
    }
}
