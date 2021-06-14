package graphics.scenery.tests.examples.cluster

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.ScreenConfig
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Plane
import graphics.scenery.primitives.TextBoard
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import org.joml.Vector4f
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ScreenConfigVisualizerExample : SceneryBase("Screen Config Visualizer", 1280, 720) {
    private val screens = ArrayList<Node>(8)

    override fun init() {
        val files = ArrayList<String>()

        val c = Context()
        val ui = c.getService(UIService::class.java)
        val file = ui.chooseFile(null, FileWidget.OPEN_STYLE)
        if(file != null) {
            files.add(file.absolutePath)
        } else {
            files.add("CAVEExample.yml")
        }

        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val lights = Light.createLightTetrahedron<PointLight>(spread = 5.0f, radius = 10.0f)
        lights.forEach { scene.addChild(it) }

        val light = PointLight(20.0f)
        light.spatial().position = Vector3f(0.0f, 1.8f, 0.0f)
        scene.addChild(light)

        val origin = Icosphere(0.1f, 2)
        origin.material().diffuse = Vector3f(1.0f)
        scene.addChild(origin)

        val screenconfig = ScreenConfig.loadFromFile(files[0])
        screenconfig.screens.forEach { (name, screen) ->
            logger.info("Adding screen $name with lowerLeft=${screen.lowerLeft}, lowerRight=${screen.lowerRight}, upperLeft=${screen.upperLeft}")

            screen.lowerLeft.z *= -1.0f
            screen.lowerRight.z *= -1.0f
            screen.upperLeft.z *= -1.0f

            val color = Random.random3DVectorFromRange(0.5f, 0.8f)
            val s = Group()

            val lowerLeft = Icosphere(0.05f, 2)
            lowerLeft.material().diffuse = Vector3f(1.0f, 0.0f, 0.0f)
            lowerLeft.spatial().position = screen.lowerLeft
            s.addChild(lowerLeft)

            val lowerRight = Icosphere(0.05f, 2)
            lowerRight.material().diffuse = Vector3f(0.0f, 1.0f, 0.0f)
            lowerRight.spatial().position = screen.lowerRight
            s.addChild(lowerRight)

            val upperLeft = Icosphere(0.05f, 2)
            upperLeft.material().diffuse = Vector3f(0.0f, 0.0f, 1.0f)
            upperLeft.spatial().position = screen.upperLeft
            s.addChild(upperLeft)

            val up: Vector3f = screen.lowerLeft + (screen.lowerRight - screen.lowerLeft) + (screen.upperLeft - screen.lowerLeft)
            val p = Plane(screen.lowerLeft, screen.upperLeft, screen.lowerRight, up)
            p.material {
                diffuse = color
                cullingMode = Material.CullingMode.None
            }

            val label = TextBoard()
            label.text = name
            label.backgroundColor = Vector4f(0.0f)
            label.fontColor = Vector4f(1.0f)
            label.spatial {
                scale = Vector3f(0.5f, 0.5f, 0.5f)
                position = Vector3f(0.0f, 1.5f, 0.0f)
            }
            label.visible = false
            s.addChild(label)

            s.addChild(p)

            screens.add(s)
            scene.addChild(s)
        }

        val box = Box(Vector3f(scene.getMaximumBoundingBox().getBoundingSphere().radius * 0.5f), insideNormals = true)
        box.material {
            cullingMode = Material.CullingMode.None
            diffuse = Vector3f(0.3f, 0.3f, 0.3f)
        }
        scene.addChild(box)


        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 1.65f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }
    }

    override fun inputSetup() {
        super.inputSetup()

        var index = 0
        val cycleScreens = ClickBehaviour { _, _ ->
            val allVisible = screens.all { it.visible }

            if(allVisible) {
                index = 0
            }

            screens.forEachIndexed { i , obj ->
                obj.visible = i == index
            }

            if(index == screens.size) {
                screens.forEach {
                    it.visible = true
                    it.children.find { c -> c is TextBoard }?.visible = false
                }
            }

            index = index + 1 % (screens.size + 1)
        }

        inputHandler?.addBehaviour("cycle_screens", cycleScreens)
        inputHandler?.addKeyBinding("cycle_screens", "N")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ScreenConfigVisualizerExample().main()
        }
    }
}

