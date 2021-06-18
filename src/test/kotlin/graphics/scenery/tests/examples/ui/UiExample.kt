package graphics.scenery.tests.examples.ui

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.tests.examples.basic.TexturedCubeExample
import graphics.scenery.textures.Texture
import graphics.scenery.ui.MenuMesh
import graphics.scenery.utils.Image
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class UiExample : SceneryBase("TexturedCubeExample") {

    override fun init() {
        renderer = hub.add(SceneryElement.Renderer, Renderer.createRenderer(hub, applicationName, scene,
                                                                            2560, 1440))

        val menu = MenuMesh(hub)
        scene.addChild(menu)

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"
        box.material.textures["diffuse"] = Texture.fromImage(Image.fromResource("textures/helix.png", TexturedCubeExample::class.java))
        box.material.metallic = 0.3f
        box.material.roughness = 0.9f
        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            while (running) {
                box.rotation.rotateY(0.01f)
                box.needsUpdate = true

                Thread.sleep(20)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            UiExample().main()
        }
    }
}

