package graphics.scenery.tests.examples.volumes

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.joml.Vector3f
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
            spatial {
                position = Vector3f(0.0f, 0.5f, 5.0f)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.renderable {
            material.cullingMode = Material.CullingMode.None
            material.diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            material.specular = Vector3f(0.0f)
            material.ambient = Vector3f(0.0f)
        }
        scene.addChild(shell)

        val s = Icosphere(0.5f, 3)
        s.spatial {
            position = Vector3f(2.0f, -1.0f, -2.0f)
        }
        s.renderable {
            material.diffuse = Vector3f(0.0f, 0.0f, 0.0f)
        }
        scene.addChild(s)

        val volume = Volume.fromPathRaw(Paths.get(getDemoFilesPath() + "/volumes/box-iso/"), hub)
        volume.name = "volume"
        volume.colormap = Colormap.get("viridis")
        volume.spatial {
            position = Vector3f(0.0f, 0.0f, -3.5f)
            rotation = rotation.rotateXYZ(0.05f, 0.05f, 0.05f)
            scale = Vector3f(20.0f, 20.0f, 20.0f)
        }
        volume.transferFunction = TransferFunction.ramp(0.1f, 0.5f)
        scene.addChild(volume)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial {
                position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            }
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        thread {
            while(true) {
                volume.spatial {
                    rotation = rotation.rotateY(0.003f)
                }
                Thread.sleep(5)
            }
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VolumeExample().main()
        }
    }
}
