package graphics.scenery.tests.examples.advanced

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Demo animating multiple boxes without instancing.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class MultiBoxExample : SceneryBase("MultiBoxExample") {
    override fun init() {
        renderer = Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
        hub.add(SceneryElement.Renderer, renderer!!)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = GLVector(10.0f, 10.0f, 10.0f)
            perspectiveCamera(60.0f, 1.0f * windowWidth, 1.0f * windowHeight, 1.0f, 1000.0f)
            active = true
            scene.addChild(this)
        }

        val boundaryWidth = 10.0
        val boundaryHeight = 10.0

        val m = Mesh()
        val boxes = (0..1000).map {
            Box(GLVector(0.2f, 0.2f, 0.2f))
        }

        boxes.mapIndexed { s, box ->

            val k: Double = s % boundaryWidth
            val j: Double = (s / boundaryWidth) % boundaryHeight
            val i: Double = s / (boundaryWidth * boundaryHeight)

            box.position = GLVector(Math.floor(i).toFloat() * 3.0f, Math.floor(j).toFloat() * 3.0f, Math.floor(k).toFloat() * 3.0f)

            m.addChild(box)
        }

        scene.addChild(m)

        val lights = (0..20).map {
            PointLight(radius = 450.0f)
        }.map {
            it.position = Random.randomVectorFromRange(3, -600.0f, 600.0f)
            it.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
            it.intensity = Random.randomFromRange(50.0f, 1000f)

            scene.addChild(it)
            it
        }

        val hullbox = Box(GLVector(100.0f, 100.0f, 100.0f), insideNormals = true)
        with(hullbox) {
            position = GLVector(0.0f, 0.0f, 0.0f)

            material.ambient = GLVector(0.6f, 0.6f, 0.6f)
            material.diffuse = GLVector(0.4f, 0.4f, 0.4f)
            material.specular = GLVector(0.0f, 0.0f, 0.0f)
            material.cullingMode = Material.CullingMode.Front

            scene.addChild(this)
        }

        var ticks: Int = 0

        thread {
            val step = 0.02f

            while (true) {
                lights.mapIndexed { i, light ->
                    val phi = Math.PI * 2.0f * ticks / 1500.0f

                    light.position = GLVector(
                        Math.exp(i.toDouble()).toFloat() * 10 * Math.sin(phi).toFloat() + Math.exp(i.toDouble()).toFloat(),
                        step * ticks,
                        Math.exp(i.toDouble()).toFloat() * 10 * Math.cos(phi).toFloat() + Math.exp(i.toDouble()).toFloat())

                }

                ticks++

                m.rotation.rotateByEuler(0.001f, 0.001f, 0.0f)
                m.needsUpdate = true

                Thread.sleep(10)
            }
        }
    }

    @Test
    override fun main() {
        super.main()
    }

}
