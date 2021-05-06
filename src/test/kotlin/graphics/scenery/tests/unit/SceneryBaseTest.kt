package graphics.scenery.tests.unit

import graphics.scenery.Camera
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.SceneryBase
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.tests.unit.backends.FauxRenderer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.joml.Vector3f
import org.junit.Test

class SceneryBaseTest {

    @Test
    fun testClose() {
        val handler = CoroutineExceptionHandler { _, e ->
            throw e
        }
        val base = object: SceneryBase(wantREPL = false, applicationName = "test") {
            override fun init() {
                super.init()
                hub.add(FauxRenderer(hub, scene, null))
                val cam: Camera = DetachedHeadCamera()

                scene.addChild(cam)
                scene.initialized = true
            }
        }
        val exampleRunnable = GlobalScope.launch(handler) {
            base.assertions[SceneryBase.AssertionCheckPoint.BeforeStart]?.forEach {
                it.invoke()
            }
            base.main()
        }

        while (!base.running || !base.sceneInitialized() || base.hub.get(SceneryElement.Renderer) == null) {
//            println("I'm here")
        }

        print("Sending close to instance")
        base.close()
        base.assertions[SceneryBase.AssertionCheckPoint.AfterClose]?.forEach {
            it.invoke()
        }

        print("closed")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SceneryBaseTest().testClose()
        }
    }
}
