package graphics.scenery.tests.examples.compute

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.tests.examples.basic.TexturedCubeExample
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import kotlin.concurrent.thread

/**
 * Example showing a glitchy compute shader inserted between the HDR and FXAA passes of the
 * default deferred shading pipeline, for fun and profit.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ComputeShaderRenderpassExample : SceneryBase("ComputeShaderRenderpassExample") {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, 512, 512, renderConfigFile = "DeferredShadingGlitchy.yml"))

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"
        box.material {
            textures["diffuse"] = Texture.fromImage(Image.fromResource("textures/helix.png", TexturedCubeExample::class.java))
            metallic = 0.3f
            roughness = 0.9f
        }
        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.spatial().position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            while (running) {
                box.spatial {
                    rotation.rotateY(0.01f)
                    needsUpdate = true
                }

                Thread.sleep(20)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ComputeShaderRenderpassExample().main()
        }
    }
}
