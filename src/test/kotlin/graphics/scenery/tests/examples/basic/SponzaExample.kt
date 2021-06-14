package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import graphics.scenery.primitives.TextBoard
import org.joml.Vector4f
import org.scijava.ui.behaviour.ClickBehaviour
import kotlin.concurrent.thread

/**
 * Demo loading the Sponza Model, demonstrating multiple moving lights
 * and transparent objects.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class SponzaExample : SceneryBase("SponzaExample", windowWidth = 1280, windowHeight = 720) {
    private var movingLights = true

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName,
            scene,
            windowWidth,
            windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 1.0f, 0.0f)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            scene.addChild(this)
        }

        val lights = (0 until 128).map {
            Box(Vector3f(0.1f, 0.1f, 0.1f))
        }.map {
            it.spatial {
                position = Vector3f(
                    Random.randomFromRange(-6.0f, 6.0f),
                    Random.randomFromRange(0.1f, 1.2f),
                    Random.randomFromRange(-10.0f, 10.0f)
                )
            }
            val light = PointLight(radius = Random.randomFromRange(0.5f, 4.0f))
            it.material {
                diffuse = Random.random3DVectorFromRange(0.1f, 0.9f)
                light.emissionColor = diffuse
            }
            light.intensity = Random.randomFromRange(0.1f, 0.5f)

            it.addChild(light)

            scene.addChild(it)
            it
        }

        val mesh = Mesh()
        with(mesh) {
            readFromOBJ(getDemoFilesPath() + "/sponza.obj", importMaterials = true)
            spatial {
                rotation.rotateY(Math.PI.toFloat() / 2.0f)
                scale = Vector3f(0.01f, 0.01f, 0.01f)
            }
            name = "Sponza Mesh"

            scene.addChild(this)
        }

        val desc = TextBoard()
        desc.text = "sponza"
        desc.spatial {
            position = Vector3f(-2.0f, -0.1f, -4.0f)
        }
        desc.fontColor = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)
        desc.backgroundColor = Vector4f(0.1f, 0.1f, 0.1f, 1.0f)
        desc.transparent = 0
        scene.addChild(desc)

        thread {
            var ticks = 0L
            while (true) {
                if(movingLights) {
                    lights.mapIndexed { i, light ->
                        val phi = (Math.PI * 2.0f * ticks / 1000.0f) % (Math.PI * 2.0f)

                        light.spatial {
                            position = Vector3f(
                                position.x(),
                                5.0f * Math.cos(phi + (i * 0.5f)).toFloat() + 5.2f,
                                position.z())
                        }
                        light.children.forEach { it.spatialOrNull()?.needsUpdateWorld = true }
                    }

                    ticks++
                }

                Thread.sleep(15)
            }
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching(keybinding = "C")

        inputHandler?.addBehaviour("toggle_light_movement", ClickBehaviour { _, _ -> movingLights = !movingLights })
        inputHandler?.addKeyBinding("toggle_light_movement", "T")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SponzaExample().main()
        }
    }
}
