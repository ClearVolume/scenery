package graphics.scenery.tests.examples.basic

import org.joml.*
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random

/**
 * Example of a curve with different baseShapes.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class CurveDifferentBaseShapes: SceneryBase("CurveDifferentBaseShapes", windowWidth = 1280, windowHeight = 720) {

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

        fun shapeGenerator(splineVerticesCount: Int): ArrayList<ArrayList<Vector3f>> {
            val shapeList = ArrayList<ArrayList<Vector3f>>(splineVerticesCount)
            val splineVerticesCountThird = splineVerticesCount/3
            val splineVerticesCountTwoThirds = splineVerticesCount*2/3
            for (i in 0 until splineVerticesCountThird) {
                val list = ArrayList<Vector3f>()
                list.add(Vector3f(0.3f, 0.3f, 0f))
                list.add(Vector3f(0.3f, -0.3f, 0f))
                list.add(Vector3f(-0.3f, -0.3f, 0f))
                shapeList.add(list)
            }
            for(i in splineVerticesCountThird until splineVerticesCountTwoThirds) {
                val list = ArrayList<Vector3f>()
                list.add(Vector3f(0.3f, 0.3f, 0f))
                list.add(Vector3f(0.3f, -0.3f, 0f))
                list.add(Vector3f(-0.3f, -0.3f, 0f))
                list.add(Vector3f(-0.3f, 0.3f, 0f))
                shapeList.add(list)
            }
            for(i in splineVerticesCountTwoThirds until splineVerticesCount) {
                val list = ArrayList<Vector3f>()
                list.add(Vector3f(0.3f, 0.3f, 0f))
                list.add(Vector3f(0.3f, -0.3f, 0f))
                list.add(Vector3f(0f, -0.5f, 0f))
                list.add(Vector3f(-0.3f, -0.3f, 0f))
                list.add(Vector3f(-0.3f, 0.3f, 0f))
                list.add(Vector3f(0f, 0.5f, 0f))
                shapeList.add(list)
            }
            return shapeList
        }

        val bSpline = UniformBSpline(points)
        val splineSize = bSpline.splinePoints().size
        val geo = Curve(bSpline, false) { shapeGenerator(splineSize) }

        scene.addChild(geo)

        val lightbox = Box(Vector3f(25.0f, 25.0f, 25.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material.diffuse = Vector3f(0.1f, 0.1f, 0.1f)
        lightbox.material.roughness = 1.0f
        lightbox.material.metallic = 0.0f
        lightbox.material.cullingMode = Material.CullingMode.None
        scene.addChild(lightbox)
        val lights = (0 until 8).map {
            val l = PointLight(radius = 20.0f)
            l.position = Vector3f(
                    Random.randomFromRange(-rowSize / 2.0f, rowSize / 2.0f),
                    Random.randomFromRange(-rowSize / 2.0f, rowSize / 2.0f),
                    Random.randomFromRange(1.0f, 5.0f)
            )
            l.emissionColor = Random.random3DVectorFromRange( 0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            lightbox.addChild(l)
            l
        }
        lights.forEach { scene.addChild(it) }

        val stageLight = PointLight(radius = 10.0f)
        stageLight.name = "StageLight"
        stageLight.intensity = 0.5f
        stageLight.position = Vector3f(0.0f, 0.0f, 5.0f)
        scene.addChild(stageLight)

        val cameraLight = PointLight(radius = 5.0f)
        cameraLight.name = "CameraLight"
        cameraLight.emissionColor = Vector3f(1.0f, 1.0f, 0.0f)
        cameraLight.intensity = 0.8f

        val cam: Camera = DetachedHeadCamera()
        cam.position = Vector3f(0.0f, 0.0f, 15.0f)
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
            CurveDifferentBaseShapes().main()
        }
    }
}
