package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.compute.EdgeBundler
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Line
import graphics.scenery.primitives.LinePair
import graphics.scenery.attribute.material.Material
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple example to demonstrate Edge Bundling
 * @author Johannes Waschke <jowaschke@cbs.mpg.de>
 */
class EdgeBundlerExample : SceneryBase("EdgeBundlerExample") {

    override fun init() {
        val eb = EdgeBundler(Line.createLines(800, 50, 10.0f, 0.25f), hub) // From Line set

        // Do the actual work. Optionally, you can change parameters before calling this function
        eb.calculate()

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        initScene()

        // Look how many clusters were created, use this information for colormap
        val colorMap = Array(eb.numberOfClusters) { Random.random3DVectorFromRange(0.2f, 0.8f) }

        eb.getLinePairsAndClusters().forEach { (track, clusterId) ->
            track.material {
                ambient = colorMap[clusterId]
                diffuse = colorMap[clusterId]
                specular = colorMap[clusterId]
            }
            scene.addChild(track)
        }
    }

    /**
     * Prepare the 3D environment
     */
    private fun initScene() {
        val hull = Box(Vector3f(250.0f, 250.0f, 250.0f), insideNormals = true)
        hull.material {
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(hull)
        val lights = (0 until 3).map {
            val l = PointLight(radius = 150.0f)
            l.intensity = 0.5f
            l.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            scene.addChild(l)
            l
        }

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, -2.0f, 15.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        scene.addChild(cam)

        // Light show
        thread {
            while(true) {
                val t = runtime/100.0f
                lights.forEachIndexed { i, pointLight ->
                    pointLight.spatial().position = Vector3f(
                        33.0f* sin(2.0f*i*PI/3.0f+t*PI/50.0f).toFloat(),
                        0.0f,
                        -33.0f* cos(2.0f*i*PI/3.0f+t*PI/50.0f).toFloat())
                }
                Thread.sleep(20)
            }
        }

        // Bundling ... de-bundling ... bundling ... de-bundling...
        thread {
            while (true) {
                val t = sin(runtime * PI/2000.0f) * 0.5f + 0.5f
                scene.children.forEach() { n-> if(n is LinePair) {n.interpolationState = t.toFloat()} }
                Thread.sleep(20)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            EdgeBundlerExample().main()
        }
    }
}
