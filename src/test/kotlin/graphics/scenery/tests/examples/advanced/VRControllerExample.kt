package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.scijava.ui.behaviour.ClickBehaviour
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Example for usage of VR controllers. Demonstrates the use of custom key bindings on the
 * HMD, and the use of intersection testing with scene elements.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VRControllerExample : SceneryBase(VRControllerExample::class.java.simpleName,
    windowWidth = 1920, windowHeight = 1200) {
    private lateinit var hmd: OpenVRHMD
    private lateinit var boxes: List<Node>
    private lateinit var hullbox: Box

    override fun init() {
        hmd = OpenVRHMD(useCompositor = true)

        if(!hmd.initializedAndWorking()) {
            logger.error("This demo is intended to show the use of OpenVR controllers, but no OpenVR-compatible HMD could be initialized.")
            exitProcess(1)
        }

        hub.add(SceneryElement.HMDInput, hmd)

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        renderer?.toggleVR()

        val cam: Camera = DetachedHeadCamera(hmd)
        cam.spatial {
            position = Vector3f(0.0f, 0.0f, 0.0f)
        }

        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)

        scene.addChild(cam)

        boxes = (0..10).map {
            val obj = Box(Vector3f(0.1f, 0.1f, 0.1f))
            obj.spatial {
                position = Vector3f(-1.0f + (it + 1) * 0.2f, 1.0f, -0.5f)
            }
            obj
        }

        boxes.forEach { scene.addChild(it) }

        (0..5).map {
            val light = PointLight(radius = 15.0f)
            light.emissionColor = Random.random3DVectorFromRange(0.0f, 1.0f)
            light.spatial {
                position = Random.random3DVectorFromRange(-5.0f, 5.0f)
            }
            light.intensity = 1.0f

            light
        }.forEach { scene.addChild(it) }

        hullbox = Box(Vector3f(20.0f, 20.0f, 20.0f), insideNormals = true)
        hullbox.material {
            ambient = Vector3f(0.6f, 0.6f, 0.6f)
            diffuse = Vector3f(0.4f, 0.4f, 0.4f)
            specular = Vector3f(0.0f, 0.0f, 0.0f)
            cullingMode = Material.CullingMode.Front
        }

        scene.addChild(hullbox)

        thread {
            while(!running) {
                Thread.sleep(200)
            }

            hmd.events.onDeviceConnect.add { hmd, device, timestamp ->
                if(device.type == TrackedDeviceType.Controller) {
                    logger.info("Got device ${device.name} at $timestamp")
                    device.model?.let { controller ->
                        // This attaches the model of the controller to the controller's transforms
                        // from the OpenVR/SteamVR system.
                        hmd.attachToNode(device, controller, cam)

                        // This update routine is called every time the position of the controller
                        // updates, and checks for intersections with any of the boxes. If there is
                        // an intersection with a box, that box is slightly nudged in the direction
                        // of the controller's velocity.
                        controller.update.add {
                            boxes.forEach { it.materialOrNull()?.diffuse = Vector3f(0.9f, 0.5f, 0.5f) }
                            boxes.filter { box -> controller.children.first().spatialOrNull()?.intersects(box) ?: false }.forEach { box ->
                                box.ifMaterial {
                                    diffuse = Vector3f(1.0f, 0.0f, 0.0f)
                                }
                                box.ifSpatial {
                                    position = (device.velocity ?: Vector3f(0.0f)) * 0.05f + position
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun inputSetup() {
        super.inputSetup()

        // We first grab the default movement actions from scenery's input handler,
        // and re-bind them on the right-hand controller's trackpad or joystick.
        inputHandler?.let { handler ->
            hashMapOf(
                "move_forward" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Up),
                "move_back" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Down),
                "move_left" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Left),
                "move_right" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Right)
            ).forEach { (name, key) ->
                handler.getBehaviour(name)?.let { b ->
                    logger.info("Adding behaviour $name bound to $key to HMD")
                    hmd.addBehaviour(name, b)
                    hmd.addKeyBinding(name, key)
                }
            }
        }

        // Now we add another behaviour for toggling visibility of the boxes
        hmd.addBehaviour("toggle_boxes", ClickBehaviour { _, _ ->
            boxes.forEach { it.visible = !it.visible }
            logger.info("Boxes visible: ${boxes.first().visible}")
        })
        // ...and bind that to the side button of the left-hand controller.
        hmd.addKeyBinding("toggle_boxes", TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Side)

        // Finally, add a behaviour to toggle the scene's shell
        hmd.addBehaviour("toggle_shell", ClickBehaviour { _, _ ->
            hullbox.visible = !hullbox.visible
            logger.info("Hull visible: ${hullbox.visible}")
        })
        //... and bind that to the A button on the left-hand controller.
        hmd.addKeyBinding("toggle_shell", TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.A)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VRControllerExample().main()
        }
    }
}
