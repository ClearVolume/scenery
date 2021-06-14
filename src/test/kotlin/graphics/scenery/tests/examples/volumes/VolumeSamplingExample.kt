package graphics.scenery.tests.examples.volumes

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Cylinder
import graphics.scenery.primitives.Line
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.RingBuffer
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.Volume
import net.imglib2.type.numeric.integer.UnsignedByteType
import org.lwjgl.system.MemoryUtil.memAlloc
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Example that renders procedurally generated volumes and samples from it.
 * [bitsPerVoxel] can be set to 8 or 16, to generate Byte or UnsignedShort volumes.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VolumeSamplingExample: SceneryBase("Volume Sampling example", 1280, 720) {
    val bitsPerVoxel = 8
    val volumeSize = 128

    enum class VolumeType { File, Procedural }

    var volumeType = VolumeType.Procedural
    lateinit var volumes: List<String>

    var playing = true
    var skipToNext = false
    var skipToPrevious = false
    var currentVolume = 0

    override fun init() {

        val files = ArrayList<String>()
        val fileFromProperty = System.getProperty("dataset")
        volumeType = if(fileFromProperty != null) {
            files.add(fileFromProperty)
            VolumeType.File
        } else {
            val c = Context()
            val ui = c.getService(UIService::class.java)
            val file = ui.chooseFile(null, FileWidget.DIRECTORY_STYLE)
            if(file != null) {
                files.add(file.absolutePath)
                VolumeType.File
            } else {
                VolumeType.Procedural
            }
        }

        if(volumeType == VolumeType.File) {
            val folder = File(files.first())
            val stackfiles = folder.listFiles()
            volumes = stackfiles.filter { it.isFile && it.name.toLowerCase().endsWith("raw") || it.name.substringAfterLast(".").toLowerCase().startsWith("tif") }.map { it.absolutePath }.sorted()
        }

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.5f, 5.0f)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material {
            cullingMode = Material.CullingMode.None
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            specular = Vector3f(0.0f)
            ambient = Vector3f(0.0f)
        }
        shell.spatial {
            position = Vector3f(0.0f, 4.0f, 0.0f)
        }
        scene.addChild(shell)

        val p1 = Icosphere(0.2f, 2)
        p1.spatial().position = Vector3f(-0.5f, 0.0f, -2.0f)
        p1.material().diffuse = Vector3f(0.3f, 0.3f, 0.8f)
        scene.addChild(p1)

        val p2 = Icosphere(0.2f, 2)
        p2.spatial().position = Vector3f(0.0f, 0.5f, 2.0f)
        p2.material().diffuse = Vector3f(0.3f, 0.8f, 0.3f)
        scene.addChild(p2)

        val connector = Cylinder.betweenPoints(p1.spatial().position, p2.spatial().position)
        connector.material().diffuse = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(connector)

        p1.update.add {
            connector.spatial().orientBetweenPoints(p1.spatial().position, p2.spatial().position, true, true)
        }

        p2.update.add {
            connector.spatial().orientBetweenPoints(p1.spatial().position, p2.spatial().position, true, true)
        }

        val volume = Volume.fromBuffer(emptyList(), volumeSize, volumeSize, volumeSize, UnsignedByteType(), hub)
        volume.name = "volume"
        volume.spatial {
            position = Vector3f(0.0f, 0.0f, 0.0f)
            scale = Vector3f(10.0f, 10.0f, 10.0f)
        }
        volume.colormap = Colormap.get("viridis")
//        volume.voxelSizeZ = 0.5f
        with(volume.transferFunction) {
            addControlPoint(0.0f, 0.0f)
            addControlPoint(0.2f, 0.0f)
            addControlPoint(0.4f, 0.5f)
            addControlPoint(0.8f, 0.5f)
            addControlPoint(1.0f, 0.0f)
        }

        volume.metadata["animating"] = true
        scene.addChild(volume)

        val bb = BoundingGrid()
        bb.node = volume

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        thread {
            while(!scene.initialized) { Thread.sleep(200) }

            val volumeSize = 128L
            val volumeBuffer = RingBuffer<ByteBuffer>(2) { memAlloc((volumeSize*volumeSize*volumeSize*bitsPerVoxel/8).toInt()) }

            val seed = Random.randomFromRange(0.0f, 133333337.0f).toLong()
            var shift = Vector3f(0.0f)
            val shiftDelta = Random.random3DVectorFromRange(-1.5f, 1.5f)

            while(running) {
                when(volumeType) {
                    VolumeType.File -> if (playing || skipToNext || skipToPrevious) {
                        val newVolume = if (skipToNext || playing) {
                            skipToNext = false
                            nextVolume()
                        } else {
                            skipToPrevious = false
                            previousVolume()
                        }

                        logger.debug("Loading volume $newVolume")
                        if (newVolume.toLowerCase().endsWith("raw")) {
                            TODO("Implement reading volumes from raw files")
                        } else {
                            TODO("Implemented reading volumes from image files")
                        }
                    }

                    VolumeType.Procedural ->
                        if (volume.metadata["animating"] == true) {
                            val currentBuffer = volumeBuffer.get()

                            Volume.generateProceduralVolume(volumeSize, 0.05f, seed = seed,
                                intoBuffer = currentBuffer, shift = shift, use16bit = bitsPerVoxel > 8)

                            volume.addTimepoint(
                                "procedural-cloud-${shift.hashCode()}", currentBuffer)

                            shift = shift + shiftDelta
                        }
                }

                val intersection = volume.spatial().intersectAABB(p1.spatial().position, (p2.spatial().position - p1.spatial().position).normalize())
                if(intersection is MaybeIntersects.Intersection) {
                    val scale = volume.localScale()
                    val localEntry = (intersection.relativeEntry + Vector3f(1.0f)) * (1.0f/2.0f)
                    val localExit = (intersection.relativeExit + Vector3f(1.0f)) * (1.0f/2.0f)
                    logger.info("Ray intersects volume at ${intersection.entry}/${intersection.exit} rel=${localEntry}/${localExit} localScale=$scale")

                    val (samples, _) = volume.sampleRay(localEntry, localExit) ?: null to null
                    logger.info("Samples: ${samples?.joinToString(",") ?: "(no samples returned)"}")

                    if(samples == null) {
                        continue
                    }

                    val diagram = if(connector.getChildrenByName("diagram").isNotEmpty()) {
                        connector.getChildrenByName("diagram").first() as Line
                    } else {
                        TODO("Implement volume size queries or refactor")
//                        val sizeX = 128
//                        val sizeY = 128
//                        val sizeZ = 128
//                        val l = Line(capacity = maxOf(sizeX, sizeY, sizeZ) * 2)
//                        connector.addChild(l)
//                        l
                    }

                    diagram.clearPoints()
                    diagram.name = "diagram"
                    diagram.edgeWidth = 0.005f
                    diagram.material().diffuse = Vector3f(0.05f, 0.05f, 0.05f)
                    diagram.spatial().position = Vector3f(0.0f, 0.0f, -0.5f)
                    diagram.addPoint(Vector3f(0.0f, 0.0f, 0.0f))
                    var point = Vector3f(0.0f)
                    samples.filterNotNull().forEachIndexed { i, sample ->
                        point = Vector3f(0.0f, i.toFloat()/samples.size, -sample)
                        diagram.addPoint(point)
                    }
                    diagram.addPoint(point)
                }

                Thread.sleep(20)
            }
        }
    }

    fun nextVolume(): String {
        val v = volumes[currentVolume % volumes.size]
        currentVolume++

        return v
    }

    fun previousVolume(): String {
        val v = volumes[currentVolume % volumes.size]
        currentVolume--

        return v
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        val toggleRenderingMode = object : ClickBehaviour {
            var modes = Volume.RenderingMethod.values()
            var currentMode = (scene.find("volume") as? Volume)!!.renderingMethod

            override fun click(x: Int, y: Int) {
                currentMode = modes.getOrElse(modes.indexOf(currentMode) + 1 % modes.size) { Volume.RenderingMethod.AlphaBlending }

                (scene.find("volume") as? Volume)?.renderingMethod = currentMode
                logger.info("Switched volume rendering mode to $currentMode")
            }
        }

        val togglePlaying = ClickBehaviour { _, _ ->
            playing = !playing
        }

        inputHandler?.addBehaviour("toggle_rendering_mode", toggleRenderingMode)
        inputHandler?.addKeyBinding("toggle_rendering_mode", "M")

        inputHandler?.addBehaviour("toggle_playing", togglePlaying)
        inputHandler?.addKeyBinding("toggle_playing", "G")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VolumeSamplingExample().main()
        }
    }
}
