package graphics.scenery.tests.examples.advanced

import graphics.scenery.*
import org.joml.*
import graphics.scenery.backends.Renderer
import graphics.scenery.geometry.CatmullRomSpline
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.proteins.Helix
import graphics.scenery.proteins.MathLine

/**
 * This is an example of how to set up a helix.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class HelixExample: SceneryBase("FlatRibbonSketch", windowWidth = 1280, windowHeight = 720) {
    override fun init() {

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val rowSize = 10f

        val helixRectangle = listOf(Vector3f(5f, 5f, 0f), Vector3f(-5f, 5f, 0f),
                Vector3f(-5f, -5f, 0f), Vector3f(5f, -5f, 0f))
        val helixSplineControlPoints = ArrayList<Vector3f>(4*5)
        var i = 0
        for(j in 0..4) {
            helixRectangle.forEach {
                val vec = Vector3f(it.x(), it.y(), i.toFloat())
                helixSplineControlPoints.add(vec)
                i++
            }
        }
        val spline = CatmullRomSpline(helixSplineControlPoints, 20)
        fun baseShape(): ArrayList<Vector3f> {
            val list = ArrayList<Vector3f>(4)
            list.add(Vector3f(0.5f, 0f, 0f))
            list.add(Vector3f(0f, 0.1f, 0f))
            list.add(Vector3f(-0.5f, 0f, 0f))
            list.add(Vector3f(0f, -0.1f, 0f))
            return list
        }
        val axis = MathLine(Vector3f(0f, 0f, 1f), Vector3f(0f, 0f, 0f))
        val curve = Helix(axis, spline) {baseShape()}

        scene.addChild(curve)

        val lightbox = Box(Vector3f(100.0f, 100.0f, 100.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material {
            diffuse = Vector3f(0.1f, 0.1f, 0.1f)
            roughness = 1.0f
            metallic = 0.0f
            cullingMode = Material.CullingMode.None
        }
        scene.addChild(lightbox)
        val lights = (0 until 8).map {
            val l = PointLight(radius = 80.0f)
            l.spatial {
                position = Vector3f(
                    Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                    Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                    Random.randomFromRange(1.0f, 5.0f)
                )
            }
            l.emissionColor = Random.random3DVectorFromRange( 0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            lightbox.addChild(l)
            l
        }

        lights.forEach { lightbox.addChild(it) }

        val stageLight = PointLight(radius = 35.0f)
        stageLight.name = "StageLight"
        stageLight.intensity = 0.5f
        stageLight.spatial().position = Vector3f(0.0f, 0.0f, 5.0f)
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
            HelixExample().main()
        }
    }
}
