package graphics.scenery.tests.examples.basic

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class FontRenderingExample: SceneryBase("FontRenderingExample", windowWidth = 1280, windowHeight = 720) {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val light = PointLight(radius = 20.0f)
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        light.intensity = 1000.0f
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(3.3f, 0.0f, 4.0f)
            perspectiveCamera(70.0f, windowWidth, windowHeight, 1.0f, 1000.0f)

            scene.addChild(this)
        }

        val box = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        box.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f)
        box.material.cullingMode = Material.CullingMode.Front
        scene.addChild(box)

        val board = TextBoard()
        board.text = "hello world"
        board.name = "TextBoard"
        board.transparent = 0
        board.fontColor = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)
        board.backgroundColor = Vector4f(100.0f, 100.0f, 0.0f, 1.0f)
        board.position = Vector3f(-4.0f, 0.0f, -4.9f)
        board.scale = Vector3f(2.0f, 2.0f, 2.0f)

        scene.addChild(board)

        animate {
            while(board.dirty) { Thread.sleep(200) }

            val text = arrayOf(
                "this is scenery.",
                "hello world."
            )

            while(running) {
                text.map {
                    Thread.sleep(2500)
                    board.text = it
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            FontRenderingExample().main()
        }
    }
}
