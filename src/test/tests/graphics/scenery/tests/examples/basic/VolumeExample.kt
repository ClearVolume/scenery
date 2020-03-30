package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Standard volume rendering example, with a volume loaded from a file.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VolumeExample: SceneryBase("Volume Rendering example", 1280, 720) {
    var hmd: TrackedStereoGlasses? = null

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            position = Vector3f(0.0f, 0.5f, 5.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            active = true

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.None
        shell.material.diffuse = Vector3f(0.2f, 0.2f, 0.2f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        scene.addChild(shell)

        val s = Icosphere(0.5f, 3)
        s.position = Vector3f(2.0f, -1.0f, -2.0f)
        s.material.diffuse = Vector3f(0.0f, 0.0f, 0.0f)
        scene.addChild(s)

        val volume = Volume()
        volume.name = "volume"
        volume.colormap = "jet"
        volume.position = Vector3f(0.0f, 0.0f, -3.5f)
        volume.rotation = volume.rotation.rotateXYZ(0.05f, 0.05f, 0.05f)
        volume.scale = Vector3f(20.0f, 20.0f, 20.0f)
        volume.transferFunction = TransferFunction.ramp(0.1f, 0.5f)
        scene.addChild(volume)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        val files: List<File> = File(getDemoFilesPath() + "/volumes/box-iso/").listFiles().toList()

        val volumes = files.filter { it.isFile }.map { it.absolutePath }.sorted()
        logger.info("Got ${volumes.size} volumes: ${volumes.joinToString(", ")}")

        var currentVolume = 0
        fun nextVolume(): String {
            val v = volumes[currentVolume % (volumes.size)]
            currentVolume++

            return v
        }

        thread {
            while(!scene.initialized || volumes.isEmpty()) { Thread.sleep(200) }

            val v = nextVolume()
            volume.readFrom(Paths.get(v), replace = true)

            logger.info("Got volume!")

            while(true) {
                volume.rotation = volume.rotation.rotateY(0.003f)
                Thread.sleep(5)
            }
        }

    }

    override fun inputSetup() {
        setupCameraModeSwitching()
    }

    @Test override fun main() {
        super.main()
    }
}
