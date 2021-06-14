package graphics.scenery.tests.examples.advanced

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.behaviours.MouseDragPlane
import graphics.scenery.controls.behaviours.MouseDragSphere
import graphics.scenery.controls.behaviours.MouseRotate
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.effectors.LineRestrictionEffector
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.extensions.plus
import org.joml.Vector3f
import kotlin.concurrent.thread

/**
 * Test for [SelectCommand], produces a lot of (clickable) spheres, that
 * wiggle when selected by double-click.
 *
 * Drag nodes along a sphere around the camera by holding "1" and moving the mouse.
 * Drag nodes along a plane parallel to the view plane by holding "2" and moving the mouse.
 * Rotate nodes by holding "3" and moving the mouse.
 *
 * The green sphere is an example of how to restrict dragging.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @author Jan Tiemann
 */
class MouseInputExample : SceneryBase("MouseInputExample", wantREPL = true) {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        for (i in 0 until 200) {
            if (i % 2 == 0) {
                val s = Icosphere(Random.randomFromRange(0.04f, 0.2f), 2)
                s.spatial().position = Random.random3DVectorFromRange(-5.0f, 5.0f)
                scene.addChild(s)
            } else {
                val box = Box(Random.random3DVectorFromRange(0.04f, 0.2f), insideNormals = true)
                box.material().diffuse = Vector3f(0f, 1.0f, 1.0f)
                box.spatial().position = Random.random3DVectorFromRange(-5.0f, 5.0f)
                scene.addChild(box)
            }
        }

        val box = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        box.material {
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(box)

        val largePlate = Box(Vector3f(7.0f, 1.0f, 8.0f))
        largePlate.material().diffuse = Vector3f(0.0f, 1.0f, 0.5f)
        largePlate.spatial().position = Vector3f(0f,-4.0f,0f)
        scene.addChild(largePlate)

        val restrictedDragSphere = Icosphere(Random.randomFromRange(0.04f, 0.2f), 2)
        restrictedDragSphere.material().diffuse = Vector3f(0f, 1.0f, 0f)
        scene.addChild(restrictedDragSphere)

        LineRestrictionEffector(restrictedDragSphere,{Vector3f(-1f,0f,0f)},{Vector3f(1f,0f,0f)})

        val light = PointLight(radius = 15.0f)
        light.spatial().position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 1.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial().position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }
    }

    override fun inputSetup() {
        super.inputSetup()

        val wiggle: (Scene.RaycastResult, Int, Int) -> Unit = { result, _, _ ->
            result.matches.firstOrNull()?.let { nearest ->
                val originalPosition = Vector3f(nearest.node.spatialOrNull()?.position)
                thread {
                    for (i in 0 until 200) {
                        nearest.node.spatialOrNull()?.position = originalPosition + Random.random3DVectorFromRange(-0.05f, 0.05f)
                        Thread.sleep(2)
                    }
                }
            }
        }

        renderer?.let { r ->
            inputHandler?.addBehaviour(
                "select", SelectCommand(
                    "select", r, scene,
                    { scene.findObserver() }, action = wiggle, debugRaycast = false
                )
            )
            inputHandler?.addKeyBinding("select", "double-click button1")
        }

        inputHandler?.addBehaviour(
            "sphereDragObject", MouseDragSphere(
                "sphereDragObject",
                { scene.findObserver() }, debugRaycast = false
            )
        )
        inputHandler?.addKeyBinding("sphereDragObject", "1")

        inputHandler?.addBehaviour(
            "planeDragObject", MouseDragPlane(
                "planeDragObject",
                { scene.findObserver() }, debugRaycast = false
            )
        )
        inputHandler?.addKeyBinding("planeDragObject", "2")

        inputHandler?.addBehaviour(
            "rotateObject", MouseRotate(
                "rotateObject",
                { scene.findObserver() }, debugRaycast = false
            )
        )
        inputHandler?.addKeyBinding("rotateObject", "3")

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MouseInputExample().main()
        }
    }
}
