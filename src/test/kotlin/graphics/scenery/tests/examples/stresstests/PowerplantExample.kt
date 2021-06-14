package graphics.scenery.tests.examples.stresstests

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import kotlin.concurrent.thread

/**
 * Stress-testing demo with the PowerPlant model from UNC, and lots of lights.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class PowerplantExample : SceneryBase("PowerplantExample", windowWidth = 1280, windowHeight = 720) {
    override fun init() {
        val lightCount = 127

        logger.warn("ADDITIONAL FILES NEEDED")
        logger.warn("This example needs an additional model file, which is not available as part of the")
        logger.warn("example models zip. Please download it from: http://graphics.cs.williams.edu/data/meshes.xml#12")

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 0.0f)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight, nearPlaneLocation = 0.5f, farPlaneLocation = 1000.0f)

            scene.addChild(this)
        }

        val boxes = (0..lightCount).map {
            Box(Vector3f(0.5f, 0.5f, 0.5f))
        }

        val lights = (0..lightCount).map {
            PointLight(radius = Random.randomFromRange(5.0f, 100.0f))
        }

        boxes.mapIndexed { i, box ->
            box.addChild(lights[i])
            scene.addChild(box)
        }

        lights.map {
            it.emissionColor = Random.random3DVectorFromRange(0.0f, 1.0f)
            it.parent?.materialOrNull()?.diffuse = it.emissionColor
            it.intensity = Random.randomFromRange(0.01f, 10f)

            scene.addChild(it)
        }

        val plant = Mesh()
        with(plant) {
            readFromOBJ(getDemoFilesPath() + "/powerplant.obj", importMaterials = true)
            spatial {
                position = Vector3f(0.0f, 0.0f, 0.0f)
                scale = Vector3f(0.001f, 0.001f, 0.001f)
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
                    val phi = Math.PI * 2.0f * ticks / 2500.0f

                    box.spatial {
                        position = Vector3f(
                            -128.0f + 18.0f * (i + 1),
                            5.0f + i * 5.0f,
                            (i + 1) * 50 * Math.cos(phi + (i * 0.2f)).toFloat())

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
            PowerplantExample().main()
        }
    }
}

