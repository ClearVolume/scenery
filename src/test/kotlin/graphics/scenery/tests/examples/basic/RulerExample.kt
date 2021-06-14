package graphics.scenery.tests.examples.basic

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.behaviours.Ruler
import graphics.scenery.attribute.material.Material
import org.joml.Vector3f

/**
 * Sketch that shows how the ruler works. Run, press R (and hold it) then you can create a line on a Mouse-click, and
 * drag it on your screen.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 */
class RulerExample: SceneryBase("RulerSketch", wantREPL = true) {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        val box = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        box.material {
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.spatial().position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 1.0f
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
    }

    override fun inputSetup() {
        super.inputSetup()
        inputHandler?.addBehaviour("ruler", Ruler("create", { scene.findObserver() }, scene))
        inputHandler?.addKeyBinding("ruler", "R")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            RulerExample().main()
        }
    }
}

