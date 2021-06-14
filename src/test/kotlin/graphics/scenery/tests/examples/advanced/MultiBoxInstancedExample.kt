package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import graphics.scenery.attribute.material.Material
import kotlin.concurrent.thread

/**
 * Demo animating multiple boxes with instancing.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class MultiBoxInstancedExample : SceneryBase("MultiBoxInstancedExample") {
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

        val boundaryWidth = 50.0
        val boundaryHeight = 50.0

        val container = Mesh()

        val b = Box(Vector3f(0.7f, 0.7f, 0.7f))
        b.name = "boxmaster"
        b.setMaterial(ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")) {
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            ambient = Vector3f(1.0f, 1.0f, 1.0f)
            specular = Vector3f(1.0f, 1.0f, 1.0f)
            metallic = 0.0f
            roughness = 1.0f
        }
        val bInstanced = InstancedNode(b)
        scene.addChild(bInstanced)

        (0 until (boundaryWidth * boundaryHeight * boundaryHeight).toInt()).map {
            val inst = bInstanced.addInstance()
            inst.name = "Box_$it"
            inst.addAttribute(Material::class.java, b.material())

            val k: Double = it.rem(boundaryWidth)
            val j: Double = (it / boundaryWidth).rem(boundaryHeight)
            val i: Double = it / (boundaryWidth * boundaryHeight)

            inst.spatial {
                position = Vector3f(Math.floor(i).toFloat(), Math.floor(j).toFloat(), Math.floor(k).toFloat())
            }

            inst.parent = container
            inst
        }

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

        val hullbox = Box(Vector3f(100.0f, 100.0f, 100.0f))
        hullbox.spatial {
            position = Vector3f(0.0f, 0.0f, 0.0f)
        }
        hullbox.name = "hullbox"
        hullbox.material {
            ambient = Vector3f(0.6f, 0.6f, 0.6f)
            diffuse = Vector3f(0.4f, 0.4f, 0.4f)
            specular = Vector3f(0.0f, 0.0f, 0.0f)
            cullingMode = Material.CullingMode.Front
        }

        scene.addChild(hullbox)

        thread {
            while (running) {
                container.spatial {
                    rotation.rotateXYZ(0.001f, 0.001f, 0.0f)
                    needsUpdateWorld = true
                    needsUpdate = true
                    updateWorld(true, false)
                }

                val inst = bInstanced.addInstance()
                inst.spatial {
                    position = Random.random3DVectorFromRange(-40.0f, 40.0f)
                }
                inst.parent = container
                bInstanced.instances.removeAt(kotlin.random.Random.nextInt(bInstanced.instances.size - 1))

                Thread.sleep(20)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MultiBoxInstancedExample().main()
        }
    }

}
