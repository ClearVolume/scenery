package graphics.scenery.tests.examples.volumes

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
import net.imglib2.RandomAccessible
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.gauss3.Gauss3
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions
import net.imglib2.cache.img.SingleCellArrayImg
import net.imglib2.converter.Converters
import net.imglib2.img.Img
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.loops.LoopBuilder
import net.imglib2.loops.LoopBuilder.TriConsumer
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.volatiles.VolatileUnsignedShortType
import net.imglib2.util.Intervals
import net.imglib2.view.Views


/**
 * Example showing rendering of a flybrain together with two lazily-generated
 * Gauss-filtered versions of the image and their difference.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @author Tobias Pietzsch <pietzsch@mpi-cbg.de>
 */
class FlybrainOutOfCoreExample: SceneryBase("Flybrain RAI Rendering example", 1280, 720) {
    lateinit var volume: Volume

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            spatial {
                position = Vector3f(0.0f, 0.0f, 15.0f)
            }
            scene.addChild(this)
        }

        logger.info("Downloading flybrain stack ...")
        val imp = IJ.openImage("https://imagej.nih.gov/ij/images/flybrain.zip")
        val flybrain: RandomAccessibleInterval<ARGBType> = ImageJFunctions.wrapRGBA(imp)
        logger.info("Done.")

        val transform = AffineTransform3D()
        val sx = imp.calibration.pixelWidth
        val sy = imp.calibration.pixelHeight
        val sz = imp.calibration.pixelDepth
        transform[sx, 0.0, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 0.0, sz] = 0.0
        val type = UnsignedShortType()

        // extract the red channel
        val red = Converters.convert(flybrain, { i: ARGBType, o: UnsignedShortType -> o.set(ARGBType.red(i.get())) }, type)

        // set up two cached CellImages that (lazily) convolve red with different sigmas
        val source: RandomAccessible<UnsignedShortType> = Views.extendBorder(red)
        val sigma1 = doubleArrayOf(5.0, 5.0, 5.0)
        val sigma2 = doubleArrayOf(4.0, 4.0, 4.0)
        val dimensions = Intervals.dimensionsAsLongArray(flybrain)
        val factory = ReadOnlyCachedCellImgFactory(
            ReadOnlyCachedCellImgOptions.options().cellDimensions(32, 32, 32))
        val gauss1: Img<UnsignedShortType> = factory.create(dimensions, type, { cell: SingleCellArrayImg<UnsignedShortType, *>? ->
            Gauss3.gauss(sigma1, source, cell, 1)
        })
        val gauss2: Img<UnsignedShortType> = factory.create(dimensions, type, { cell: SingleCellArrayImg<UnsignedShortType, *>? ->
            Gauss3.gauss(sigma2, source, cell, 1)
        })

        // set up another cached CellImages that (lazily) computes the absolute difference of gauss1 and gauss2
        val diff: Img<UnsignedShortType> = factory.create(dimensions, type, { cell: SingleCellArrayImg<UnsignedShortType, *>? ->
            LoopBuilder.setImages(
                    Views.interval(gauss1, cell),
                    Views.interval(gauss2, cell),
                    cell)
                .forEachPixel(TriConsumer { in1: UnsignedShortType, in2: UnsignedShortType, out: UnsignedShortType -> out.set(10 * Math.abs(in1.get() - in2.get())) })
        })

        val brain = Volume.fromRAI(
            red as RandomAccessibleInterval<UnsignedShortType>,
            UnsignedShortType(),
            name = "flybrain",
            hub = hub
        )
        brain.transferFunction = TransferFunction.ramp(0.001f, 0.4f)
        brain.converterSetups.first().setDisplayRange(0.0, 255.0)
        brain.spatial().position = Vector3f(-3.0f, 3.0f, 0.0f)

        @Suppress("UNCHECKED_CAST")
        val gaussDiff = Volume.fromRAI(
            VolatileViews.wrapAsVolatile(diff) as RandomAccessibleInterval<VolatileUnsignedShortType>,
            VolatileUnsignedShortType(),
            name = "diff of gauss1 and gauss2",
            hub = hub
        )
        gaussDiff.transferFunction = TransferFunction.ramp(0.1f, 0.1f)
        gaussDiff.converterSetups.first().setDisplayRange(0.0, 255.0)
        gaussDiff.spatial().position = Vector3f(3.0f, 3.0f, 0.0f)

        @Suppress("UNCHECKED_CAST")
        val brainGauss1 = Volume.fromRAI(
            VolatileViews.wrapAsVolatile(gauss1) as RandomAccessibleInterval<VolatileUnsignedShortType>,
            VolatileUnsignedShortType(),
            name = "gauss1",
            hub = hub
        )
        brainGauss1.transferFunction = TransferFunction.ramp(0.1f, 0.1f)
        brainGauss1.converterSetups.first().setDisplayRange(0.0, 60.0)
        brainGauss1.spatial().position = Vector3f(-3.0f, -3.0f, 0.0f)

        @Suppress("UNCHECKED_CAST")
        val brainGauss2 = Volume.fromRAI(
            VolatileViews.wrapAsVolatile(gauss2) as RandomAccessibleInterval<VolatileUnsignedShortType>,
            VolatileUnsignedShortType(),
            name = "gauss2",
            hub = hub
        )
        brainGauss2.transferFunction = TransferFunction.ramp(0.1f, 0.1f)
        brainGauss2.converterSetups.first().setDisplayRange(0.0, 60.0)
        brainGauss2.spatial().position = Vector3f(3.0f, -3.0f, 0.0f)

        scene.addChild(brain)
        scene.addChild(gaussDiff)
        scene.addChild(brainGauss1)
        scene.addChild(brainGauss2)

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
            FlybrainOutOfCoreExample().main()
        }
    }
}
