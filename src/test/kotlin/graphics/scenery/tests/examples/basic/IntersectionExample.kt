package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.material.Material
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class IntersectionExample: SceneryBase("IntersectionExample") {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        val boxmaterial = DefaultMaterial()
        with(boxmaterial) {
            ambient = Vector3f(1.0f, 0.0f, 0.0f)
            diffuse = Vector3f(0.0f, 1.0f, 0.0f)
            specular = Vector3f(1.0f, 1.0f, 1.0f)
            roughness = 0.3f
            metallic = 1.0f
        }

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"

        val box2 = Box(Vector3f(1.0f, 1.0f, 1.0f))

        with(box) {
            box.addAttribute(Material::class.java, boxmaterial)
            scene.addChild(this)
        }

        with(box2) {
            spatial {
                position = Vector3f(-1.5f, 0.0f, 0.0f)
            }
            box2.addAttribute(Material::class.java,  boxmaterial)
            scene.addChild(this)
        }
        val light = PointLight(radius = 15.0f)
        light.spatial {
            position = Vector3f(0.0f, 0.0f, 2.0f)
        }
        light.intensity = 100.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            while (true) {
                if (box.spatial().intersects(box2)) {
                    box.material().diffuse = Vector3f(1.0f, 0.0f, 0.0f)
                } else {
                    box.material().diffuse = Vector3f(0.0f, 1.0f, 0.0f)
                }

                Thread.sleep(20)
            }
        }
    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            IntersectionExample().main()
        }
    }
}
