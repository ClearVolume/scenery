package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.Mesh
import graphics.scenery.mesh.MeshImporter
import graphics.scenery.mesh.TextBoard
import graphics.scenery.numerics.Random
import org.joml.Vector4f
import org.junit.Test
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
            cam.position = Vector3f(0.0f, 1.0f, 0.0f)
            cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
            scene.addChild(this)
        }

        val lights = (0 until 1).map {
            Box(Vector3f(0.1f, 0.1f, 0.1f))
        }.map {
            it.position = Vector3f(
                Random.randomFromRange(-6.0f, 6.0f),
                Random.randomFromRange(0.1f, 1.2f),
                Random.randomFromRange(-10.0f, 10.0f)
            )

            it.material.diffuse = Random.random3DVectorFromRange(0.1f, 0.9f)

            val light = PointLight(radius = Random.randomFromRange(5.5f, 50.0f))
            light.emissionColor = it.material.diffuse
            light.intensity = Random.randomFromRange(0.1f, 0.5f)

            it.addChild(light)

            scene.addChild(it)
            it
        }

        MeshImporter.readFromOBJ(getDemoFilesPath() + "/sponza.obj", importMaterials = false).apply {
            rotation.rotateY(Math.PI.toFloat() / 2.0f)
            scale = Vector3f(0.01f, 0.01f, 0.01f)
            name = "Sponza Mesh"

            scene.addChild(this)
        }

        val desc = TextBoard()
        desc.text = "sponza"
        desc.position = Vector3f(-2.0f, -0.1f, -4.0f)
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

                        light.position = Vector3f(
                            light.position.x(),
                            5.0f * Math.cos(phi + (i * 0.5f)).toFloat() + 5.2f,
                            light.position.z())

                        light.children.forEach { it.needsUpdateWorld = true }
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

    @Test override fun main() {
        super.main()
    }
}
