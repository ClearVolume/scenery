package graphics.scenery.tests.examples.advanced

import graphics.scenery.*
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.utils.extensions.minus
import org.joml.Vector3f
import kotlin.math.floor

/**
 * Example cycling different rendering qualities.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class CycleRenderQualityExample: SceneryBase("CycleRenderQualityExample", windowWidth = 1280, windowHeight = 720) {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val rowSize = 10f
        val spheres = (0 until 100).map {
            val s = Icosphere(0.4f, 2)
            s.position = Vector3f(
                floor(it / rowSize),
                (it % rowSize.toInt()).toFloat(),
                0.0f)
            s.position = s.position - Vector3f(
                (rowSize - 1.0f)/2.0f,
                (rowSize - 1.0f)/2.0f,
                0.0f)
            s.material.roughness = (it / rowSize)/rowSize
            s.material.metallic = (it % rowSize.toInt())/rowSize
            s.material.diffuse = Vector3f(1.0f, 0.0f, 0.0f)

            s
        }

        spheres.forEach { scene.addChild(it) }

        val lightbox = Box(Vector3f(25.0f, 25.0f, 25.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material.diffuse = Vector3f(0.1f, 0.1f, 0.1f)
        lightbox.material.roughness = 1.0f
        lightbox.material.metallic = 0.0f
        lightbox.material.cullingMode = Material.CullingMode.None
        scene.addChild(lightbox)

        val lights = (0 until 8).map {
            val l = PointLight(radius = 20.0f)
            l.position = Vector3f(
                Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                Random.randomFromRange(1.0f, 5.0f)
            )
            l.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            l
        }

        lights.forEach { lightbox.addChild(it) }

        val stageLight = PointLight(radius = 35.0f)
        stageLight.name = "StageLight"
        stageLight.intensity = 0.5f
        stageLight.position = Vector3f(0.0f, 0.0f, 5.0f)
        scene.addChild(stageLight)

        val cameraLight = PointLight(radius = 5.0f)
        cameraLight.name = "CameraLight"
        cameraLight.emissionColor = Vector3f(1.0f, 1.0f, 0.0f)
        cameraLight.intensity = 0.8f

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.2f, 12.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        cam.addChild(cameraLight)

        var quality = 0
        animate {
            waitForSceneInitialisation()

            while(running) {
                renderer?.setRenderingQuality(RenderConfigReader.RenderingQuality.values().get(quality))
                quality = (quality + 1) % RenderConfigReader.RenderingQuality.values().size
                Thread.sleep(2500)
            }
        }
    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            CycleRenderQualityExample().main()
        }
    }
}
