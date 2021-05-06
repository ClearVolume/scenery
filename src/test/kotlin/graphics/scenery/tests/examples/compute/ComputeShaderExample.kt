package graphics.scenery.tests.examples.compute

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.tests.examples.basic.TexturedCubeExample
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import kotlinx.coroutines.*
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import org.scijava.ui.behaviour.ClickBehaviour

/**
 * Example showing simple image processing with compute shaders.
 * Based on [graphics.scenery.tests.examples.basic.TexturedCubeExample].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ComputeShaderExample : SceneryBase("ComputeShaderExample") {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        val helix = Texture.fromImage(Image.fromResource("textures/helix.png", TexturedCubeExample::class.java), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        val buffer = MemoryUtil.memCalloc(helix.dimensions.x * helix.dimensions.y * helix.dimensions.z * 4)

        val compute = Node()
        compute.name = "compute node"
        compute.material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("BGRAMosaic.comp"), this::class.java))
        compute.material.textures["OutputViewport"] = Texture.fromImage(Image(buffer, helix.dimensions.x, helix.dimensions.y, helix.dimensions.z), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material.textures["InputColor"] = helix
        compute.metadata["ComputeMetadata"] = ComputeMetadata(
            workSizes = Vector3i(512, 512, 1),
            invocationType = InvocationType.Once
        )

        scene.addChild(compute)

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"
        box.material.textures["diffuse"] = compute.material.textures["OutputViewport"]!!
        box.material.metallic = 0.3f
        box.material.roughness = 0.9f

        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        animateLoop(20) {
            box.rotation.rotateY(0.01f)
            box.needsUpdate = true
//            box.material.textures["diffuse"] = compute.material.textures["OutputViewport"]!!
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun inputSetup() {
        super.inputSetup()

        inputHandler?.addBehaviour("save_texture", ClickBehaviour { _, _ ->
            logger.info("Finding node")
            val node = scene.find("compute node") ?: return@ClickBehaviour
            val texture = node.material.textures["OutputViewport"]!!
            val r = renderer ?: return@ClickBehaviour
            logger.info("Node found, saving texture")

            val result = r.requestTexture(texture) { tex ->
                logger.info("Received texture")

                tex.contents?.let { buffer ->
                    logger.info("Dumping to file")
                    SystemHelpers.dumpToFile(buffer, "texture-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
                    logger.info("File dumped")
                }

            }
            result.getCompleted()

        })
        inputHandler?.addKeyBinding("save_texture", "E")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ComputeShaderExample().main()
        }
    }
}
