package graphics.scenery.tests.examples.basic

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import org.junit.Test
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class TexturedCubeExample : SceneryBase("TexturedCubeExample") {
    override fun init() {
        renderer = Renderer.createRenderer(hub, applicationName, scene, 512, 512)
        hub.add(SceneryElement.Renderer, renderer!!)

        val boxmaterial = Material()
        with(boxmaterial) {
            ambient = GLVector(1.0f, 0.0f, 0.0f)
            diffuse = GLVector(0.0f, 1.0f, 0.0f)
            specular = GLVector(1.0f, 1.0f, 1.0f)
            roughness = 0.3f
            metallic = 1.0f
            textures.put("diffuse", TexturedCubeExample::class.java.getResource("textures/helix.png").file)
        }

        val box = Box(GLVector(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"

        with(box) {
            box.material = boxmaterial
            scene.addChild(this)
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
            while (true) {
                box.rotation.rotateByAngleY(0.01f)
                box.needsUpdate = true

                Thread.sleep(20)
            }
        }
    }

    @Test
    override fun main() {
        super.main()
    }
}
