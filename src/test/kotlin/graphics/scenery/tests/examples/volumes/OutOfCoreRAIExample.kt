package graphics.scenery.tests.examples.volumes

import bdv.util.AxisOrder
import bdv.util.volatiles.VolatileViews
import org.joml.Vector3f
import graphics.scenery.Camera
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.PointLight
import graphics.scenery.SceneryBase
import graphics.scenery.backends.Renderer
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import ij.IJ
import ij.ImagePlus
import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.Img
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.volatiles.VolatileUnsignedShortType
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSReader
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import tpietzsch.example2.VolumeViewerOptions
import java.nio.file.Files


/**
 * BDV Rendering Example loading an out-of-core RAI
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @author Tobias Pietzsch <pietzsch@mpi-cbg.de>
 */
class OutOfCoreRAIExample: SceneryBase("Out-of-core RAI Rendering example", 1280, 720) {
    lateinit var volume: Volume

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        cam.spatial().position = Vector3f(0.0f, 0.0f, 7.0f)
        scene.addChild(cam)

        // download example TIFF stack, T1 head example from NIH
        val imp: ImagePlus = IJ.openImage("https://imagej.nih.gov/ij/images/t1-head.zip")
        val img: Img<UnsignedShortType> = ImageJFunctions.wrapShort(imp)

        // create multiresolution N5 dataset from the T1 head example dataset
        // and save it to a temporary location.
        val datasetName = "testDataset"
        val n5path = Files.createTempDirectory("scenery-t1-head-n5")
        val n5 = N5FSWriter(n5path.toString())
        N5Utils.save(img, n5, datasetName, intArrayOf(img.dimension(0).toInt(), img.dimension(1).toInt(), img.dimension(2).toInt()), GzipCompression())

        // load the multiresolution N5 dataset from the temporary location and wrap it
        // as [net.imglib2.Volatile] dataset.
        val ooc: RandomAccessibleInterval<UnsignedShortType> = N5Utils.openVolatile(N5FSReader(n5path.toString()), datasetName)
        val wrapped = VolatileViews.wrapAsVolatile(ooc)

        // When loading datasets with multiple resolution levels, it's important to use Volatile types
        // here, such as VolatileUnsignedShortType, otherwise loading volume blocks will not work correctly.
        @Suppress("UNCHECKED_CAST")
        volume = Volume.fromRAI(
            wrapped as RandomAccessibleInterval<VolatileUnsignedShortType>,
            VolatileUnsignedShortType(),
            AxisOrder.DEFAULT,
            "T1 head OOC",
            hub,
            VolumeViewerOptions()
        )
        volume.converterSetups.first().setDisplayRange(25.0, 512.0)
        volume.transferFunction = TransferFunction.ramp(0.01f, 0.03f)
        volume.spatial().scale = Vector3f(1.0f, 1.0f, 3.0f)
        scene.addChild(volume)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 50.0f
            scene.addChild(light)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            OutOfCoreRAIExample().main()
        }
    }
}
