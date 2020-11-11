package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.mesh.Box
import graphics.scenery.numerics.Random
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import org.junit.Test
import org.scijava.ui.behaviour.ClickBehaviour

/**
 * This example demonstrates how to use the TargetArcBallBehaviour and how
 * to modify the default behaviour/key map of scenery, and also manually
 * trigger behaviours. See also [SceneryBase.setupCameraModeSwitching].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ArcballExample : SceneryBase("ArcballExample") {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 1024, 1024))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 2.5f)
            perspectiveCamera(70.0f, windowWidth, windowHeight)

            targeted = true
            target = Vector3f(0.0f, 0.0f, 0.0f)

            scene.addChild(this)
        }

        val camlight = PointLight(3.0f)
        camlight.intensity = 5.0f
        cam.addChild(camlight)

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))

        with(box) {
            box.position = Vector3f(0.0f, 0.0f, 0.0f)

            material.ambient = Vector3f(1.0f, 0.0f, 0.0f)
            material.diffuse = Vector3f(0.0f, 1.0f, 0.0f)
            material.specular = Vector3f(1.0f, 1.0f, 1.0f)
            material.textures["diffuse"] = Texture.fromImage(Image.fromResource("textures/helix.png", TexturedCubeExample::class.java))

            scene.addChild(this)
        }

        val lights = (0..2).map {
            PointLight(radius = 15.0f)
        }.map { light ->
            light.position = Random.random3DVectorFromRange(-3.0f, 3.0f)
            light.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)
            light.intensity = Random.randomFromRange(0.1f, 0.8f)
            light
        }

        val floor = Box(Vector3f(500.0f, 0.05f, 500.0f))
        floor.position = Vector3f(0.0f, -1.0f, 0.0f)
        floor.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(floor)

        lights.forEach(scene::addChild)
    }

    override fun inputSetup() {
        setupCameraModeSwitching(keybinding = "C")

        // switch to arcball mode by manually triggering the behaviour
        (inputHandler?.getBehaviour("toggle_control_mode") as ClickBehaviour).click(0, 0)
    }

    @Test override fun main() {
        super.main()
    }
}
