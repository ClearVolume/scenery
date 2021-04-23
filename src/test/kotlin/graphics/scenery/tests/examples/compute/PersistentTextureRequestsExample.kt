package graphics.scenery.tests.examples.compute

import bdv.util.AxisOrder
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import graphics.scenery.volumes.VolumeManager
import ij.IJ
import ij.ImagePlus
import net.imglib2.img.Img
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import tpietzsch.example2.VolumeViewerOptions
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/**
 * Example to show how persistent texture requests - that are served once per frame - may be created
 * and accessed. These may be particularly be useful, e.g, when compute shaders are used and output
 * textures need to be accessed every time they are updated.
 *
 * @author Aryaman Gupta <argupta@mpi-cbg.de>
 * @author Ulrik Günther <hello@ulrik.is>
 */
class PersistentTextureRequestsExample : SceneryBase("PersistentTextureRequestsExample") {

    private val counter = AtomicInteger(0)
    private var totalFrames = -1L

    override fun init() {
        renderer = hub.add(
            SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, 1280, 720))

        val volumeManager = VolumeManager(hub,
            useCompute = true,
            customSegments = hashMapOf(
                SegmentType.FragmentShader to SegmentTemplate(
                    this.javaClass,
                    "ComputeVolume.comp",
                    "intersectBoundingBox", "vis", "SampleVolume", "Convert", "Accumulate"),
            ))

        val outputBuffer = MemoryUtil.memCalloc(1280*720*4)
        val outputTexture = Texture.fromImage(Image(outputBuffer, 1280, 720), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        volumeManager.material.textures["OutputRender"] = outputTexture

        hub.add(volumeManager)

        val imp: ImagePlus = IJ.openImage("https://imagej.nih.gov/ij/images/t1-head.zip")
        val img: Img<UnsignedShortType> = ImageJFunctions.wrapShort(imp)

        val volume = Volume.fromRAI(img, UnsignedShortType(), AxisOrder.DEFAULT, "T1 head", hub, VolumeViewerOptions())
        volume.transferFunction = TransferFunction.ramp(0.001f, 0.5f, 0.3f)
        scene.addChild(volume)

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"
        box.material.textures["diffuse"] = outputTexture
        box.material.metallic = 0.0f
        box.material.roughness = 1.0f

        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            val opTexture = volumeManager.material.textures["OutputRender"]!!

            var prevCounter = 0

            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add(opTexture to counter)

            // this is the loop where you may do your tasks that are asynchronous to rendering
            while (renderer?.shouldClose == false) {
                while (counter.get() == prevCounter) {
                    Thread.sleep(5)
                }
                prevCounter = counter.get()

                //updated texture has been returned
                val buffer = opTexture.contents
                //the buffer can now, e.g., be transmitted, as is required for parallel rendering

                if (buffer != null && prevCounter == 100) {
                    SystemHelpers.dumpToFile(buffer, "texture-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
                }
            }
        }

        thread {
            while (renderer?.firstImageReady == false) {
                Thread.sleep(5)
            }

            Thread.sleep(1000) //give some time for the rendering to take place

            renderer?.close()
            Thread.sleep(200) //give some time for the renderer to close

            totalFrames = renderer?.totalFrames!!
        }
    }

    override fun main() {
        // add assertions, these only get called when the example is called
        // as part of scenery's integration tests
        assertions[AssertionCheckPoint.AfterClose]?.add {

            assertEquals ( counter.get().toLong(), totalFrames, "One texture was returned per render frame" )
        }
        super.main()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            PersistentTextureRequestsExample().main()
        }
    }
}