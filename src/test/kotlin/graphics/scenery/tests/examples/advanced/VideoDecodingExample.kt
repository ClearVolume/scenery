package graphics.scenery.tests.examples.advanced

import graphics.scenery.BufferUtils
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.FullscreenObject
import graphics.scenery.SceneryBase
import graphics.scenery.backends.Renderer
import graphics.scenery.textures.Texture
import graphics.scenery.utils.VideoDecoder
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3i
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * Example to show programmatic video decoding. It also demonstrates how the [FullscreenObject] class and its associated shaders may
 * be used to display an image in full screen.
 *
 * @author Aryaman Gupta <argupta@mpi-cbg.de>
 */
class VideoDecodingExample : SceneryBase("VideoDecodingExample", 600, 600, wantREPL = false) {

    var buffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    var decodedFrameCount: Int = 0

    override fun init () {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam = DetachedHeadCamera()

        with(cam) {
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            spatial {
                position = Vector3f(3.213f, 8.264E-1f, -9.844E-1f)
                rotation = Quaternionf(3.049E-2, 9.596E-1, -1.144E-1, -2.553E-1)
            }
            scene.addChild(this)
        }


        val plane = FullscreenObject()
        scene.addChild(plane)

        settings.set("Renderer.HDR.Exposure", 0.05f)

        val videoDecoder = VideoDecoder(this::class.java.getResource("SampleVideo.mp4").path)
        logger.info("video decoder object created")
        thread {
            while (!sceneInitialized()) {
                Thread.sleep(200)
            }

            decodedFrameCount = 1

            while (videoDecoder.nextFrameExists) {
                val image = videoDecoder.decodeFrame()  /* the decoded image is returned as a ByteArray, and can now be processed.
                                                        Here, it is simply displayed in fullscreen */

                if(image != null) { // image can be null, e.g. when the decoder encounters invalid information between frames
                    drawFrame(image, videoDecoder.videoWidth, videoDecoder.videoHeight, plane, decodedFrameCount)
                    decodedFrameCount++
                }
            }
            decodedFrameCount -= 1
            logger.info("Done decoding and displaying $decodedFrameCount frames.")
        }
    }

    private fun drawFrame(tex: ByteArray, width: Int, height: Int, plane: FullscreenObject, frameIndex: Int) {

        if(frameIndex % 100 == 0) {
            logger.info("Displaying frame $frameIndex")
        }

        if(buffer.capacity() == 0) {
            buffer = BufferUtils.allocateByteAndPut(tex)
        } else {
            buffer.put(tex).flip()
        }

        plane.material {
            textures["diffuse"] = Texture(Vector3i(width, height, 1), 4, contents = buffer, mipmap = true)
        }
    }

    override fun main() {
        // add assertions, these only get called when the example is called
        // as part of scenery's integration tests
        assertions[AssertionCheckPoint.AfterClose]?.add {
            assertTrue ( decodedFrameCount == 105, "All frames of the video were read and decoded" )
        }

        super.main()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VideoDecodingExample().main()
        }
    }

}
