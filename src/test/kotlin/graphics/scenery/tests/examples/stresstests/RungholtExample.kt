package graphics.scenery.tests.examples.stresstests

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import kotlin.concurrent.thread

/**
 * Stress-testing demo with the Rungholt model, and even greater number of lights.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class RungholtExample : SceneryBase("RungholtExample", windowWidth = 1280, windowHeight = 720) {
    var hmd: OpenVRHMD? = null
    override fun init() {
        val lightCount = 512

        logger.warn("ADDITIONAL FILES NEEDED")
        logger.warn("This example needs an additional model file, which is not available as part of the")
        logger.warn("example models zip. Please download it from: http://graphics.cs.williams.edu/data/meshes.xml#13")

        hmd = hub.add(OpenVRHMD(useCompositor = true))
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            perspectiveCamera(50.0f, windowWidth, windowHeight, nearPlaneLocation = 0.5f, farPlaneLocation = 1000.0f)
            spatial {
                position = Vector3f(0.0f, 50.0f, -100.0f)
            }
            scene.addChild(this)
        }

        val boxes = (0..lightCount).map {
            Box(Vector3f(0.5f, 0.5f, 0.5f))
        }

        val lights = (0..lightCount).map {
            PointLight(radius = 5.0f)
        }

        boxes.mapIndexed { i, box ->
            box.addChild(lights[i])
            scene.addChild(box)
        }

        lights.map {
            it.emissionColor = Random.random3DVectorFromRange(0.0f, 1.0f)
            it.parent?.materialOrNull()?.diffuse = it.emissionColor
            it.intensity = Random.randomFromRange(0.1f, 10f)

            scene.addChild(it)
        }

        val rungholtMesh = Mesh()
        with(rungholtMesh) {
            readFromOBJ(getDemoFilesPath() + "/rungholt.obj", importMaterials = true)
            spatial {
                position = Vector3f(0.0f, 0.0f, 0.0f)
                scale = Vector3f(1.0f, 1.0f, 1.0f)
                updateWorld(true, true)
            }
            name = "rungholt"

            scene.addChild(this)
        }

        thread {
            var ticks = 0L
            while (true) {
                boxes.mapIndexed {
                    i, box ->
                    val phi = ticks / 1500.0f % (Math.PI * 2.0f)

                    box.spatial {
                        position = Vector3f(
                            -320.0f + 5.0f * (i + 1),
                            15.0f + i * 0.2f,
                            250.0f * Math.cos(phi + (i * 0.2f)).toFloat())
                        box.children[0].spatialOrNull()?.position = position
                    }

                }

                ticks++
                Thread.sleep(10)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            RungholtExample().main()
        }
    }
}

