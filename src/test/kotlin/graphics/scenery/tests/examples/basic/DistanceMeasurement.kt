package graphics.scenery.tests.examples.basic

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Line
import graphics.scenery.primitives.TextBoard
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.Spatial
import graphics.scenery.utils.extensions.plus
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.concurrent.thread

/**
 * Example of how to measure the distance between two nodes.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 */
class DistanceMeasurement: SceneryBase("RulerPick", wantREPL = true) {

    private var secondNode = false

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        for(i in 0 until 200) {
            val s = Icosphere(Random.randomFromRange(0.04f, 0.2f), 2)
            s.spatial {
                position = Random.random3DVectorFromRange(-5.0f, 5.0f)
            }
            scene.addChild(s)
        }

        val box = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        box.material {
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.spatial {
            position = Vector3f(0.0f, 0.0f, 2.0f)
        }
        light.intensity = 1.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            cam.spatial {
                position = Vector3f(0.0f, 0.0f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }
    }

    override fun inputSetup() {
        super.inputSetup()

        var lastSpatial: Spatial? = null
        val wiggle: (Scene.RaycastResult, Int, Int) -> Unit = { result, _, _ ->
            result.matches.firstOrNull()?.let { nearest ->
                nearest.node.ifSpatial {
                    val originalPosition = Vector3f(this.position)
                    thread {
                        for(i in 0 until 200) {
                            this.position = originalPosition  + Random.random3DVectorFromRange(-0.05f, 0.05f)
                            Thread.sleep(2)
                        }
                    }
                    if(!secondNode) {
                        lastSpatial = this
                    }
                    else {
                        val position0 = lastSpatial?.position ?: Vector3f(0f, 0f, 0f)
                        val position1 = this.position
                        val lastToPresent = Vector3f()
                        logger.info("distance: ${position1.sub(position0, lastToPresent).length()}")
                        val line = Line(simple = true)
                        line.addPoint(position0)
                        line.addPoint(position1)
                        scene.addChild(line)
                        val board = TextBoard()
                        board.text = "Distance: ${lastToPresent.length()} units"
                        board.name = "DistanceTextBoard"
                        board.transparent = 0
                        board.fontColor = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)
                        board.backgroundColor = Vector4f(100f, 100f, 100f, 1.0f)
                        val boardPosition = Vector3f()
                        position0.add(position1, boardPosition)
                        board.spatial {
                            position = boardPosition.mul(0.5f)
                            scale = Vector3f(0.5f, 0.5f, 0.5f)
                        }
                        scene.addChild(board)
                    }
                    secondNode = !secondNode
                }
            }
        }

        renderer?.let { r ->
            inputHandler?.addBehaviour("select", SelectCommand("select", r, scene,
                { scene.findObserver() }, action = wiggle, debugRaycast = false))
            inputHandler?.addKeyBinding("select", "double-click button1")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DistanceMeasurement().main()
        }
    }
}

