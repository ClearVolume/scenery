package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.Hololens
import graphics.scenery.numerics.Random
import graphics.scenery.utils.RingBuffer
import graphics.scenery.utils.extensions.plus
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.Volume
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.lwjgl.system.MemoryUtil.memAlloc
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Example that renders procedurally generated volumes on a [Hololens].
 * [bitsPerVoxel] can be set to 8 or 16, to generate Byte or UnsignedShort volumes.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ARExample: SceneryBase("AR Volume Rendering example", 1280, 720) {
    val hololens = Hololens()
    val bitsPerVoxel = 16

    override fun init() {
        hub.add(hololens)
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        renderer?.toggleVR()

        val cam: Camera = DetachedHeadCamera(hololens)
        with(cam) {
            position = Vector3f(-0.2f, 0.0f, 1.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val volume = Volume.fromBuffer(emptyList(), 64, 64, 64, UnsignedShortType(), hub)
        volume.name = "volume"
        volume.colormap = Colormap.get("plasma")
        volume.scale = Vector3f(0.02f, 0.02f, 0.02f)
        scene.addChild(volume)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 50.0f
            scene.addChild(light)
        }

        animate {
            while(!scene.initialized) { Thread.sleep(200) }

            val volumeSize = 64L
            val volumeBuffer = RingBuffer<ByteBuffer>(2) { memAlloc((volumeSize*volumeSize*volumeSize*bitsPerVoxel/8).toInt()) }

            val seed = Random.randomFromRange(0.0f, 133333337.0f).toLong()
            var shift = Vector3f(0.0f)
            val shiftDelta = Random.random3DVectorFromRange(-0.5f, 0.5f)

            while(running) {
                val currentBuffer = volumeBuffer.get()

                Volume.generateProceduralVolume(volumeSize, 0.95f, seed = seed,
                    intoBuffer = currentBuffer, shift = shift, use16bit = bitsPerVoxel > 8)

                volume.addTimepoint("procedural-cloud-${shift.hashCode()}", currentBuffer)

                shift = shift + shiftDelta

                Thread.sleep(200)
            }
        }

        thread {
            while(true) {
//                volume.rotation = volume.rotation.rotateY(0.005f)

                Thread.sleep(15)
            }
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ARExample().main()
        }
    }
}
