package graphics.scenery.tests.examples.advanced

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.geometry.CatmullRomSpline
import graphics.scenery.geometry.Curve
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import org.joml.Vector3f

/**
 * Example of a beta strand visualized with an arrow.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class BetaStrandExample: SceneryBase("BetaStrandExample", windowWidth = 1280, windowHeight = 720) {


    override fun init() {

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val rowSize = 10f

        val points = ArrayList<Vector3f>()
        points.add(Vector3f(-8f, -9f, -9f))
        points.add(Vector3f(-7f, -5f, -7f))
        points.add(Vector3f(-5f, -5f, -5f))
        points.add(Vector3f(-4f, -2f, -3f))
        points.add(Vector3f(-2f, -3f, -4f))
        points.add(Vector3f(-1f, -1f, -1f))
        points.add(Vector3f(0f, 0f, 0f))
        points.add(Vector3f(2f, 1f, 0f))

        fun betaStrand(splineVerticesCount: Int): ArrayList<ArrayList<Vector3f>> {
            val shapeList = ArrayList<ArrayList<Vector3f>>(splineVerticesCount)
            val seventyeightPercent = (splineVerticesCount*0.78).toInt()
            for (i in 0 until seventyeightPercent) {
                val list = ArrayList<Vector3f>()
                list.add(Vector3f(0.08f, 0.3f, 0f))
                list.add(Vector3f(-0.08f, 0.3f, 0f))
                list.add(Vector3f(-0.08f, -0.3f, 0f))
                list.add(Vector3f(0.08f, -0.3f, 0f))
                shapeList.add(list)
            }
            val twentytwoPercent = splineVerticesCount-seventyeightPercent
            for(i in twentytwoPercent downTo 1) {
                val y = 0.65f*i/twentytwoPercent
                val x = 0.08f
                val arrowHeadList = ArrayList<Vector3f>(twentytwoPercent)
                arrowHeadList.add(Vector3f(x, y, 0f))
                arrowHeadList.add(Vector3f(-x, y, 0f))
                arrowHeadList.add(Vector3f(-x, -y, 0f))
                arrowHeadList.add(Vector3f(x, -y, 0f))
                shapeList.add(arrowHeadList)
            }
            return shapeList
        }

        val catmullRom = CatmullRomSpline(points, 30)
        val splineSize = catmullRom.splinePoints().size
        val geo = Curve(catmullRom) { betaStrand(splineSize) }

        scene.addChild(geo)

        val lightbox = Box(Vector3f(25.0f, 25.0f, 25.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material {
            diffuse = Vector3f(0.1f, 0.1f, 0.1f)
            roughness = 1.0f
            metallic = 0.0f
            cullingMode = Material.CullingMode.None
        }
        scene.addChild(lightbox)
        val lights = (0 until 8).map {
            val l = PointLight(radius = 20.0f)
            l.spatial {
                position = Vector3f(
                    Random.randomFromRange(-rowSize / 2.0f, rowSize / 2.0f),
                    Random.randomFromRange(-rowSize / 2.0f, rowSize / 2.0f),
                    Random.randomFromRange(1.0f, 5.0f)
                )
            }
            l.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            lightbox.addChild(l)
            l
        }

        lights.forEach { lightbox.addChild(it) }

        val stageLight = PointLight(radius = 10.0f)
        stageLight.name = "StageLight"
        stageLight.intensity = 0.5f
        stageLight.spatial {
            position = Vector3f(0.0f, 0.0f, 5.0f)
        }
        scene.addChild(stageLight)

        val cameraLight = PointLight(radius = 5.0f)
        cameraLight.name = "CameraLight"
        cameraLight.emissionColor = Vector3f(1.0f, 1.0f, 0.0f)
        cameraLight.intensity = 0.8f

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, 0.0f, 15.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        scene.addChild(cam)
        cam.addChild(cameraLight)

    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            BetaStrandExample().main()
        }
    }
}
