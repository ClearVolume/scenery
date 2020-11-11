package graphics.scenery.tests.unit.controls

import graphics.scenery.mesh.Mesh
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.utils.LazyLogger
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [OpenVRHMD].
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class OpenVRHMDTests {
    private val logger by LazyLogger()

    /**
     * Companion object for checking whether OpenVR is installed.
     */
    companion object {
        @JvmStatic @BeforeClass
        fun checkForOpenVR() {
            val hmd = OpenVRHMD()
            org.junit.Assume.assumeTrue(hmd.initializedAndWorking())
        }
    }
    private fun initialiseAndWait(): OpenVRHMD {
        val hmd = OpenVRHMD()
        while(!hmd.initializedAndWorking()) {
            Thread.sleep(200)
        }

        return hmd
    }


    /**
     * Tests OpenVR initialisation.
     */
    @Test
    fun testInitialisation() {
        logger.info("Testing OpenVR initialisation ...")
        val hmd = initialiseAndWait()
        hmd.update()
        hmd.close()
    }

    /**
     * Tests querying poses from OpenVR.
     */
    @Test
    fun testGetPosition() {
        logger.info("Testing OpenVR pose query ...")
        val hmd = initialiseAndWait()

        hmd.update()
        val poses = hmd.getPose(TrackedDeviceType.HMD)
        assertTrue(poses.isNotEmpty(), "Tracked poses are expected not to be empty.")
        hmd.close()
    }

    /**
     * Tests getting Vulkan instance extensions. These can be queried
     * without having a Vulkan instance.
     */
    @Test
    fun testGetVulkanInstanceExtensions() {
        logger.info("Testing getting Vulkan instance extensions from OpenVR ...")
        val hmd = initialiseAndWait()

        hmd.update()
        val extensions = hmd.getVulkanInstanceExtensions()
        assertTrue(extensions.isNotEmpty(), "Vulkan instance extensions are expected not to be empty.")
        hmd.close()
    }

    /**
     * Tests getting projection matrices for both eyes.
     */
    @Test
    fun testGetProjections() {
        logger.info("Testing getting projections from OpenVR ...")
        val hmd = initialiseAndWait()

        val left = hmd.getEyeProjection(0)
        val right = hmd.getEyeProjection(1)

        assertNotNull(left, "Left projection must not be null")
        assertNotNull(right, "Right projection must not be null")

        hmd.close()
    }

    /**
     * Tests loading of models of tracked objects.
     */
    @Test
    fun testLoadModels() {
        logger.info("Testing loading models ...")
        val hmd = initialiseAndWait()
        TrackedDeviceType.values().forEach { type ->
            val mesh = Mesh()
            hmd.loadModelForMesh(type, mesh)

            if(type in listOf(TrackedDeviceType.HMD, TrackedDeviceType.Controller, TrackedDeviceType.Generic, TrackedDeviceType.BaseStation)) {
                assertTrue(mesh.children.isNotEmpty(), "loadModelForMesh should populate mesh with children for $type")
            }
        }

        hmd.close()
    }

    /**
     * Tests getting the HMD orientation via OpenVR as quaternion.
     */
    @Test
    fun testGetOrientation() {
        logger.info("Testing getting HMD orientation ...")
        val hmd = initialiseAndWait()

        hmd.update()
        val o = hmd.getOrientation()
        assertFalse(!(o.x == 0.0f && o.y == 0.0f && o.z == 0.0f && o.w == 1.0f), "HMD orientation should not be identity.")
    }

    /**
     * Tests getting the transformation between head and eyes for both eyes.
     */
    @Test
    fun testGetHeadToEyeTransform() {
        logger.info("Testing querying head-to-eye transforms ...")
        val hmd = initialiseAndWait()

        val left = hmd.getHeadToEyeTransform(0)
        val right = hmd.getHeadToEyeTransform(1)
        assertNotNull(left)
        assertNotNull(right)
    }
}
