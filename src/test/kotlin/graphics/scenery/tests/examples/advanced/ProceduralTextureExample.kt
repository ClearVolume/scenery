package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.textures.Texture
import net.imglib2.type.numeric.integer.UnsignedByteType
import org.joml.Vector3i
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Example demonstrating procedural texturing using [Texture].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ProceduralTextureExample : SceneryBase("ProceduralTextureExample") {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        val boxmaterial = DefaultMaterial()
        with(boxmaterial) {
            ambient = Vector3f(1.0f, 0.0f, 0.0f)
            diffuse = Vector3f(0.0f, 1.0f, 0.0f)
            specular = Vector3f(1.0f, 1.0f, 1.0f)
        }

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du procedurale"

        with(box) {
            setMaterial(boxmaterial)
            scene.addChild(this)
        }

        val lights = (0..2).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial {
                position = Vector3f(2.0f * i, 2.0f * i, 2.0f * i)
            }
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 3.0f)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        thread {
            val imageSizeX = 256
            val imageSizeY = 256
            val imageChannels = 3
            val textureBuffer = BufferUtils.allocateByte(imageSizeX * imageSizeY * imageChannels)
            var ticks = 0L

            while(true) {
                if(box.lock.tryLock(2, TimeUnit.MILLISECONDS)) {
                    box.spatial {
                        rotation.rotateY(0.01f)
                        needsUpdate = true
                    }

                    textureBuffer.generateProceduralTextureAtTick(ticks,
                        imageSizeX, imageSizeY, imageChannels)

                    box.material {
                        textures.put("diffuse",
                            Texture(
                                Vector3i(imageSizeX, imageSizeY, 1),
                                channels = imageChannels, contents = textureBuffer,
                                type = UnsignedByteType()))
                    }
                    box.lock.unlock()

                } else {
                    logger.debug("unsuccessful lock")
                }

                Thread.sleep(50)
                ticks++
            }
        }
    }

    /**
     * Generates a procedural texture inside the [ByteBuffer].
     *
     * @param[tick] The time parameter for the generated texture.
     */
    private fun ByteBuffer.generateProceduralTextureAtTick(tick: Long, width: Int, height: Int, channels: Int) {
        val b = this.duplicate()
        val rgba = byteArrayOf(0, 0, 0, 255.toByte())

        (0 until width * height).forEach {
            val x = it % width
            val y = it / height

            val g = (255*Math.sin(0.1*x + 0.1*y + tick/10.0f)).toInt().toByte()
            val m = (Math.sin(tick/100.0) * g).toInt().toByte()
            rgba[0] = g
            rgba[1] = m
            rgba[2] = m

            b.put(rgba.take(channels).toByteArray())
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ProceduralTextureExample().main()
        }
    }
}

