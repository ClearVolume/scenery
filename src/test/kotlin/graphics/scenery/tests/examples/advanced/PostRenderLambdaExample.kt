package graphics.scenery.tests.examples.advanced

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import org.joml.Vector3f
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/**
 * Example to show how post render lambdas may be used to produce animations that are
 * synchronized with the render loop
 *
 * @author Aryaman Gupta <argupta@mpi-cbg.de>
 */
class PostRenderLambdaExample : SceneryBase("PosRenderLambdaExample") {

    private val boxRotation = Vector3f(0.0f)
    private var totalFrames = 0L
    private val quantumOfRotation = 0.01f

    override fun init() {
        renderer = hub.add(
            SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, 700, 700)
        )

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"
        box.material.metallic = 0.3f
        box.material.roughness = 0.9f
        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 0.5f, 0.5f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        renderer?.postRenderLambdas?.add {
            box.rotation.rotateY(quantumOfRotation)
            box.needsUpdate = true
        }

        thread {
            while (renderer?.firstImageReady == false) {
                Thread.sleep(5)
            }

            Thread.sleep(1000) //give some time for the rendering to take place

            renderer?.close()
            Thread.sleep(200) //give some time for the renderer to close

            box.rotation.getEulerAnglesXYZ(boxRotation)
            totalFrames = renderer?.totalFrames!!
        }
    }

    override fun main() {
        // add assertions, these only get called when the example is called
        // as part of scenery's integration tests
        assertions[AssertionCheckPoint.AfterClose]?.add {
            val testBox = Box(Vector3f(1.0f, 1.0f, 1.0f))

            var cnt = 0
            while(cnt<totalFrames) {
                testBox.rotation.rotateY(quantumOfRotation)
                cnt++
            }

            val test = Vector3f(-1.0f)
            testBox.rotation.getEulerAnglesXYZ(test)

            assertEquals ( test.y, boxRotation.y, "Rotation of box was applied once per render frame" )
        }
        super.main()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            PostRenderLambdaExample().main()
        }
    }
}