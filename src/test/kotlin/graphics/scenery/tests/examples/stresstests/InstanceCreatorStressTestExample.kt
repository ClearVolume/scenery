package graphics.scenery.tests.examples.stresstests

import graphics.scenery.numerics.Random
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import kotlin.math.roundToLong

class InstanceCreatorStressTestExample {
    fun main() {
        repeat(100) {
            val appName = memUTF8("InstanceCreator")
            val engineName = memUTF8("InstanceCreatorEngine")

            val applicationInfo = VkApplicationInfo.calloc()
            applicationInfo
                .apiVersion(VK_MAKE_VERSION(1, 1, 0))
                .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(engineName)
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .pApplicationName(appName)

            val createInfo = VkInstanceCreateInfo.calloc()
            createInfo
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(applicationInfo)
                .ppEnabledExtensionNames(null)
                .ppEnabledLayerNames(null)

            val instance = memAllocPointer(1)
            val error = vkCreateInstance(createInfo, null, instance)
            println("$it: Created instance ${instance.get(0)} with API version ${applicationInfo.apiVersion()}, engine version ${applicationInfo.engineVersion()}, return = $error")

            if(error != 0) {
                return@repeat
            }

            val vkInstance = VkInstance(instance.get(0), createInfo)

            Thread.sleep(Random.randomFromRange(50.0f, 500.0f).roundToLong())

            vkDestroyInstance(vkInstance, null)
            println("$it: Destroyed instance ${instance.get(0)}")

            memFree(appName)
            memFree(engineName)
            createInfo.free()
            applicationInfo.free()
            instance.free()
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            InstanceCreatorStressTestExample().main()
        }
    }
}

