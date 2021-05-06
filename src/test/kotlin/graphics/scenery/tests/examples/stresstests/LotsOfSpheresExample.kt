package graphics.scenery.tests.examples.stresstests

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.joml.Vector3f

/**
 * Stress test generating a lot of spheres
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class LotsOfSpheresExample: SceneryBase("LotsOfSpheres", wantREPL = true) {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        for(i in 0 until 12000) {
            val s = Sphere(0.1f, 10)
            s.position = Random.random3DVectorFromRange(-10.0f, 10.0f)
            scene.addChild(s)
        }

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 100.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        animate {
            for(i in 0 until 12000) {
                val s = Sphere(0.1f, 10)
                s.position = Random.random3DVectorFromRange(-10.0f, 10.0f)
                scene.addChild(s)
                Thread.sleep(5)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            LotsOfSpheresExample().main()
        }
    }
}
