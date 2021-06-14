package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import graphics.scenery.attribute.material.Material
import kotlin.concurrent.thread

/**
 * Demo animating multiple boxes without instancing.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class MultiBoxExample : SceneryBase("MultiBoxExample") {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(10.0f, 10.0f, 10.0f)
            }
            perspectiveCamera(60.0f, windowWidth, windowHeight, 1.0f, 1000.0f)
            scene.addChild(this)
        }

        val boundaryWidth = 10.0
        val boundaryHeight = 10.0

        val m = Mesh()
        val boxes = (0 until 1000).map {
            Box(Vector3f(1.8f, 1.8f, 1.8f))
        }

        boxes.mapIndexed {
            s, box ->

            val k: Double = s % boundaryWidth
            val j: Double = (s / boundaryWidth) % boundaryHeight
            val i: Double = s / (boundaryWidth * boundaryHeight)

            box.spatial {
                position = Vector3f(Math.floor(i).toFloat() * 3.0f, Math.floor(j).toFloat() * 3.0f, Math.floor(k).toFloat() * 3.0f)
            }
            box.material {
                diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            }

            m.addChild(box)
        }

        scene.addChild(m)

        val lights = (0..20).map {
            PointLight(radius = 250.0f)
        }.map {
            it.spatial {
                position = Random.random3DVectorFromRange(-100.0f, 100.0f)
            }
            it.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            it.intensity = Random.randomFromRange(0.1f, 0.5f)
            it
        }

        lights.forEach { scene.addChild(it) }

        val hullbox = Box(Vector3f(100.0f, 100.0f, 100.0f), insideNormals = true)
        with(hullbox) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 0.0f)
            }

            material {
                ambient = Vector3f(0.6f, 0.6f, 0.6f)
                diffuse = Vector3f(0.4f, 0.4f, 0.4f)
                specular = Vector3f(0.0f, 0.0f, 0.0f)
                cullingMode = Material.CullingMode.Front
            }

            scene.addChild(this)
        }

        thread {
            while (true) {
                m.spatial {
                    rotation.rotateXYZ(0.001f, 0.001f, 0.0f)
                    needsUpdate = true
                }

                Thread.sleep(10)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MultiBoxExample().main()
        }
    }

}
