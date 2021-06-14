package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Line
import graphics.scenery.attribute.material.Material
import kotlin.concurrent.thread

/**
 * Simple example to demonstrate the drawing of 3D lines.
 *
 * This example will draw a nicely illuminated bundle of lines using
 * the [Line] class. The line's width will oscillate while 3 lights
 * circle around the scene.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class LineExample : SceneryBase("LineExample") {
    protected var lineAnimating = true

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val hull = Box(Vector3f(50.0f, 50.0f, 50.0f), insideNormals = true)
        hull.material {
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(hull)

        val line = Line(transparent = false)
        line.addPoint(Vector3f(-5.0f, -5.0f, -5.0f))
        line.addPoint(Vector3f(0.0f, 0.0f, 0.0f))
        line.addPoint(Vector3f(5.0f, 5.0f, 5.0f))

        line.material {
            ambient = Vector3f(1.0f, 0.0f, 0.0f)
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            specular = Vector3f(1.0f, 1.0f, 1.0f)
        }
        line.spatial {
            position = Vector3f(0.0f, 0.0f, 0.0f)
        }

        line.edgeWidth = 0.02f

        scene.addChild(line)

        val lights = (0 until 2).map {
            val l = PointLight(radius = 4.0f)
            l.intensity = 1.0f
            l.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)

            scene.addChild(l)
            l
        }

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, 0.0f, 15.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        cam.target = Vector3f(0.0f, 0.0f, 0.0f)

        scene.addChild(cam)

        thread {
            while (true) {
                val t = runtime/100
                if (lineAnimating) {
                    line.addPoint(Random.random3DVectorFromRange(-5.0f, 5.0f))
                    line.edgeWidth = 0.01f * Math.sin(t * Math.PI / 50).toFloat() + 0.015f
                }

                Thread.sleep(100)
            }
        }

        thread {
            while(true) {
                val t = runtime/100
                lights.forEachIndexed { i, pointLight ->
                    pointLight.spatial {
                        position = Vector3f(
                            3.0f*Math.sin(2*i*Math.PI/3.0f+t*Math.PI/50).toFloat(),
                            0.0f,
                            -3.0f*Math.cos(2*i*Math.PI/3.0f+t*Math.PI/50).toFloat())
                    }
                }

                Thread.sleep(20)
            }
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching(keybinding = "C")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            LineExample().main()
        }
    }
}
