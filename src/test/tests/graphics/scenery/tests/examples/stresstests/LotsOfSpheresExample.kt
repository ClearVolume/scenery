package graphics.scenery.tests.examples.stresstests

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Stress test generating a lot of spheres
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class LotsOfSpheresExample : SceneryBase("LotsOfSpheres", wantREPL = true) {
    override fun init() {
        renderer = Renderer.createRenderer(hub, applicationName, scene, 512, 512)
        hub.add(SceneryElement.Renderer, renderer!!)

        for (i in 0 until 12000) {
            val s = Sphere(0.1f, 10)
            s.position = Random.randomVectorFromRange(3, -10.0f, 10.0f)
            scene.addChild(s)
        }

        val light = PointLight(radius = 15.0f)
        light.position = GLVector(0.0f, 0.0f, 2.0f)
        light.intensity = 100.0f
        light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = GLVector(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512.0f, 512.0f)
            active = true

            scene.addChild(this)
        }

        thread {
            for (i in 0 until 12000) {
                val s = Sphere(0.1f, 10)
                s.position = Random.randomVectorFromRange(3, -10.0f, 10.0f)
                scene.addChild(s)
                Thread.sleep(5)
            }
        }
    }

    @Test
    override fun main() {
        super.main()
    }
}
