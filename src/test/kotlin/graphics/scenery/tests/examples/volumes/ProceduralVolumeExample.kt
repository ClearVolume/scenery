package graphics.scenery.tests.examples.volumes

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.utils.RingBuffer
import graphics.scenery.utils.extensions.plus
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.Volume
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.lwjgl.system.MemoryUtil.memAlloc
import org.scijava.ui.behaviour.ClickBehaviour
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Example that renders procedurally generated volumes.
 * [bitsPerVoxel] can be set to 8 or 16, to generate Byte or UnsignedShort volumes.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ProceduralVolumeExample: SceneryBase("Procedural Volume Rendering Example", 1280, 720) {
    val bitsPerVoxel = 8
    val volumeSize = 128L

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.5f, 5.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.None
        shell.material.diffuse = Vector3f(0.1f, 0.1f, 0.1f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        shell.position = Vector3f(0.0f, 4.0f, 0.0f)
        scene.addChild(shell)

        val volume = if(bitsPerVoxel == 8) {
            Volume.fromBuffer(emptyList(), volumeSize.toInt(), volumeSize.toInt(), volumeSize.toInt(), UnsignedByteType(), hub)
        } else {
            Volume.fromBuffer(emptyList(), volumeSize.toInt(), volumeSize.toInt(), volumeSize.toInt(), UnsignedShortType(), hub)
        }

        volume.name = "volume"
        volume.position = Vector3f(0.0f, 0.0f, 0.0f)
        volume.colormap = Colormap.get("hot")
        volume.pixelToWorldRatio = 0.03f

        with(volume.transferFunction) {
            addControlPoint(0.0f, 0.0f)
            addControlPoint(0.2f, 0.0f)
            addControlPoint(0.4f, 0.5f)
            addControlPoint(0.8f, 0.5f)
            addControlPoint(1.0f, 0.0f)
        }

        volume.metadata["animating"] = true
        scene.addChild(volume)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.2f
            scene.addChild(light)
        }

        thread {
            val volumeBuffer = RingBuffer<ByteBuffer>(2, default = { memAlloc((volumeSize*volumeSize*volumeSize*bitsPerVoxel/8).toInt()) })

            val seed = Random.randomFromRange(0.0f, 133333337.0f).toLong()
            var shift = Vector3f(0.0f)
            val shiftDelta = Random.random3DVectorFromRange(-1.5f, 1.5f)

            var count = 0
            while(running && !shouldClose) {
                if(volume.metadata["animating"] == true) {
                    val currentBuffer = volumeBuffer.get()

                    Volume.generateProceduralVolume(volumeSize, 0.35f, seed = seed,
                        intoBuffer = currentBuffer, shift = shift, use16bit = bitsPerVoxel > 8)

                        volume.addTimepoint("t-${count}", currentBuffer)
                        volume.goToLastTimepoint()

                        volume.purgeFirst(10, 10)

                    shift += shiftDelta
                    count++
                }

                Thread.sleep(kotlin.random.Random.nextLong(10L, 200L))
            }
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        val toggleRenderingMode = object : ClickBehaviour {
            var modes = Volume.RenderingMethod.values()
            var currentMode = (scene.find("volume") as? Volume)?.renderingMethod?.ordinal ?: 0

            override fun click(x: Int, y: Int) {
                currentMode = (currentMode + 1) % modes.size

                (scene.find("volume") as? Volume)?.renderingMethod = Volume.RenderingMethod.values().get(currentMode)
                logger.info("Switched volume rendering mode to ${modes[currentMode]} (${(scene.find("volume") as? Volume)?.renderingMethod})")
            }
        }

        inputHandler?.addBehaviour("toggle_rendering_mode", toggleRenderingMode)
        inputHandler?.addKeyBinding("toggle_rendering_mode", "M")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ProceduralVolumeExample().main()
        }
    }
}
