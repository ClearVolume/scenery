package graphics.scenery.tests.examples.volumes

import bdv.spimdata.XmlIoSpimDataMinimal
import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.utils.RingBuffer
import graphics.scenery.volumes.Volume
import org.lwjgl.system.MemoryUtil
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget
import tpietzsch.example2.VolumeViewerOptions
import java.nio.ByteBuffer
import java.util.*

/**
 * BDV Rendering Example
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class BigAndSmallVolumeExample: SceneryBase("BDV + SDV Rendering example", 1280, 720) {
    lateinit var volume: Volume
    var currentCacheSize = 1024

    override fun init() {
        val files = ArrayList<String>()

        val fileFromProperty = System.getProperty("bdvXML")
        if(fileFromProperty != null) {
            files.add(fileFromProperty)
        } else {
            val c = Context()
            val ui = c.getService(UIService::class.java)
            val file = ui.chooseFile(null, FileWidget.OPEN_STYLE)
            files.add(file.absolutePath)
        }

        if(files.size == 0) {
            throw IllegalStateException("You have to select a file, sorry.")
        }

        logger.info("Loading BDV XML from ${files.first()}")

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            perspectiveCamera(50.0f, windowWidth, windowHeight)

//            position = Vector3f(170.067406f, -138.45601f, -455.9538f)
//            rotation = Quaternion(-0.05395214f, 0.94574946f, -0.23843345f, 0.21400182f)

            scene.addChild(this)
        }

        val options = VolumeViewerOptions().maxCacheSizeInMB(1024)
        val v = Volume.fromSpimData(XmlIoSpimDataMinimal().load(files.first()), hub, options)
        v.name = "volume"
//        v.colormap = "plasma"
        v.spatial().scale = Vector3f(0.02f, 0.02f, 0.02f)
        scene.addChild(v)

        volume = v

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 50.0f
            scene.addChild(light)
        }

        logger.info("Generating procedural volume")
        val volumeSize = 128L
        val bitsPerVoxel = 16
        val volumeBuffer = RingBuffer<ByteBuffer>(2) { MemoryUtil.memAlloc((volumeSize*volumeSize*volumeSize*bitsPerVoxel/8).toInt()) }

        val seed = Random.randomFromRange(0.0f, 133333337.0f).toLong()
        var shift = Vector3f(0.0f)

        val currentBuffer = volumeBuffer.get()

        Volume.generateProceduralVolume(volumeSize, 0.35f, seed = seed,
            intoBuffer = currentBuffer, shift = shift, use16bit = bitsPerVoxel > 8)

        // TODO: Bring this back
        /*
        volume.readFromBuffer(
            "procedural-cloud-${shift.hashCode()}", currentBuffer,
            volumeSize, volumeSize, volumeSize, 1.0f, 1.0f, 1.0f,
            dataType = dataType, bytesPerVoxel = bitsPerVoxel / 8, assign = false)
         */
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        val nextTimePoint = ClickBehaviour { _, _ -> volume.nextTimepoint() }
        val prevTimePoint = ClickBehaviour { _, _ -> volume.previousTimepoint() }
        val tenTimePointsForward = ClickBehaviour { _, _ ->
            val current = volume.currentTimepoint
            volume.goToTimepoint(current + 10)
        }
        val tenTimePointsBack = ClickBehaviour { _, _ ->
            val current = volume.currentTimepoint
            volume.goToTimepoint(current - 10)
        }

        inputHandler?.addBehaviour("prev_timepoint", prevTimePoint)
        inputHandler?.addKeyBinding("prev_timepoint", "H")

        inputHandler?.addBehaviour("10_prev_timepoint", tenTimePointsBack)
        inputHandler?.addKeyBinding("10_prev_timepoint", "shift H")

        inputHandler?.addBehaviour("next_timepoint", nextTimePoint)
        inputHandler?.addKeyBinding("next_timepoint", "L")

        inputHandler?.addBehaviour("10_next_timepoint", tenTimePointsForward)
        inputHandler?.addKeyBinding("10_next_timepoint", "shift L")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            BigAndSmallVolumeExample().main()
        }
    }
}
