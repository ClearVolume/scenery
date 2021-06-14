@file:Suppress("DEPRECATION")

package graphics.scenery.volumes

import bdv.BigDataViewer
import bdv.ViewerImgLoader
import bdv.cache.CacheControl
import bdv.spimdata.SpimDataMinimal
import bdv.spimdata.WrapBasicImgLoader
import bdv.spimdata.XmlIoSpimDataMinimal
import bdv.tools.brightness.ConverterSetup
import bdv.util.AxisOrder
import bdv.util.AxisOrder.DEFAULT
import bdv.util.RandomAccessibleIntervalSource
import bdv.util.RandomAccessibleIntervalSource4D
import bdv.util.volatiles.VolatileView
import bdv.util.volatiles.VolatileViewData
import bdv.viewer.DisplayMode
import bdv.viewer.Source
import bdv.viewer.SourceAndConverter
import bdv.viewer.state.ViewerState
import graphics.scenery.*
import graphics.scenery.numerics.OpenSimplexNoise
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.DelegatesProperties
import graphics.scenery.attribute.DelegationType
import graphics.scenery.attribute.geometry.DelegatesGeometry
import graphics.scenery.attribute.geometry.Geometry
import graphics.scenery.attribute.material.DelegatesMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.renderable.DelegatesRenderable
import graphics.scenery.attribute.renderable.Renderable
import graphics.scenery.attribute.spatial.DefaultSpatial
import graphics.scenery.attribute.spatial.HasCustomSpatial
import graphics.scenery.utils.LazyLogger
import graphics.scenery.volumes.Volume.VolumeDataSource.SpimDataMinimalSource
import io.scif.SCIFIO
import io.scif.util.FormatTools
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription
import mpicbg.spim.data.sequence.FinalVoxelDimensions
import net.imglib2.RandomAccessibleInterval
import net.imglib2.Volatile
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.FloatType
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3i
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import org.scijava.io.location.FileLocation
import tpietzsch.example2.VolumeViewerOptions
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.properties.Delegates
import kotlin.streams.toList

@Suppress("DEPRECATION")
open class Volume(val dataSource: VolumeDataSource, val options: VolumeViewerOptions, val hub: Hub) : DefaultNode("Volume"),
    DelegatesProperties, DelegatesRenderable, DelegatesGeometry, DelegatesMaterial, DisableFrustumCulling, HasCustomSpatial<Volume.VolumeSpatial> {

    private val delegationType: DelegationType = DelegationType.OncePerDelegate
    override fun getDelegationType(): DelegationType {
        return delegationType
    }

    private var delegateRenderable: Renderable?
    private var delegateMaterial: Material?
    private var delegateGeometry: Geometry?

    override fun getDelegateRenderable(): Renderable? {
        return delegateRenderable
    }

    fun setDelegateRenderable(delegate: Renderable?) {
        delegateRenderable = delegate
    }

    override fun getDelegateGeometry(): Geometry? {
        return delegateGeometry
    }

    fun setDelegateGeometry(delegate: Geometry?) {
        delegateGeometry = delegate
    }

    override fun getDelegateMaterial(): Material? {
        return delegateMaterial
    }

    fun setDelegateMaterial(delegate: Material?) {
        delegateMaterial = delegate
    }

    val converterSetups = ArrayList<ConverterSetup>()
    var timepointCount: Int
    val viewerState: ViewerState

    /** The transfer function to use for the volume. Flat by default. */
    var transferFunction: TransferFunction = TransferFunction.flat(0.5f)

    /** The color map for the volume. */
    var colormap: Colormap = Colormap.get("viridis")
        set(m) {
            field = m
            volumeManager.removeCachedColormapFor(this)
        }

    /** Pixel-to-world scaling ratio. Default: 1 px = 1mm in world space*/
    var pixelToWorldRatio: Float by Delegates.observable(0.001f) { property, old, new -> spatial().propertyChanged(property, old, new, "pixelToWorldRatio") }

    /** What to use as the volume's origin, scenery's default is [Origin.Center], BVV's default is [Origin.FrontBottomLeft]. **/
    var origin = Origin.Center

    /** Rendering method */
    var renderingMethod = RenderingMethod.AlphaBlending
        set(value) {
            field = value
            volumeManager.renderingMethod = value
        }

    /** Plane equations for slicing planes mapped to origin */
    var slicingPlaneEquations = mapOf<SlicingPlane, Vector4f>()

    /** Modes how assigned slicing planes interact with the volume */
    var slicingMode = SlicingMode.None

    enum class SlicingMode(val id: Int){
        // Volume is rendered as it is
        None(0),
        // Volume is cut along the assigned slicing plane and the lower half is rendered.
        // For multiple slicing planes the inner hull is rendered.
        Cropping(1),
        // Only a slice around the slicing planes is rendered with no transparency.
        Slicing(2),
        // The slice around the slicing planes is rendered with no transparency
        // while also the cropping rule applies for the rest of the volume.
        Both(3)
    }

    var volumeManager: VolumeManager

    // TODO IS THIS REQUIRED??
    var cacheControls = CacheControl.CacheControls()

    /** Current timepoint. */
    var currentTimepoint: Int
        get() { return viewerState.currentTimepoint }
        set(value) {viewerState.currentTimepoint = value}

    sealed class VolumeDataSource {
        class SpimDataMinimalSource(val spimData : SpimDataMinimal) : VolumeDataSource()
        class RAISource<T: NumericType<T>>(
            val type: NumericType<T>,
            val sources: List<SourceAndConverter<T>>,
            val converterSetups: ArrayList<ConverterSetup>,
            val numTimepoints: Int,
            val cacheControl: CacheControl? = null) : VolumeDataSource()
    }

    /**
     * Enum class for selecting a rendering method.
     */
    enum class RenderingMethod {
        MaxProjection,
        MinProjection,
        AlphaBlending
    }

    init {
        name = "Volume"

        addSpatial()

        when(dataSource) {
            is SpimDataMinimalSource -> {
                val spimData = dataSource.spimData

                val seq: AbstractSequenceDescription<*, *, *> = spimData.sequenceDescription
                timepointCount = seq.timePoints.size() - 1
                cacheControls.addCacheControl((seq.imgLoader as ViewerImgLoader).cacheControl)

                // wraps legacy image formats (e.g., TIFF) if referenced in BDV XML
                WrapBasicImgLoader.wrapImgLoaderIfNecessary(spimData)

                val sources = ArrayList<SourceAndConverter<*>>()
                // initialises setups and converters for all channels, and creates source.
                // These are then stored in [converterSetups] and [sources_].
                BigDataViewer.initSetups(spimData, converterSetups, sources)

                viewerState = ViewerState(sources, timepointCount)

                WrapBasicImgLoader.removeWrapperIfPresent(spimData)
            }

            is VolumeDataSource.RAISource<*> -> {
                timepointCount = dataSource.numTimepoints
                // FIXME: bigdataviewer-core > 9.0.0 doesn't enjoy having 0 timepoints anymore :-(
                // We tell it here to have a least one, so far no ill side effects from that
                viewerState = ViewerState(dataSource.sources, max(1, timepointCount))
                converterSetups.addAll( dataSource.converterSetups )
            }
        }

        viewerState.sources.forEach { s -> s.isActive = true }
        viewerState.displayMode = DisplayMode.FUSED

        converterSetups.forEach {
            it.color = ARGBType(Int.MAX_VALUE)
        }

        val vm = hub.get<VolumeManager>()
        val volumes = ArrayList<Volume>(10)

        if(vm != null) {
            volumes.addAll(vm.nodes)
            hub.remove(vm)
        }

        volumeManager = hub.add(VolumeManager(hub))
        volumeManager.add(this)
        volumes.forEach {
            volumeManager.add(it)
            it.delegateRenderable = volumeManager.renderable()
            it.delegateGeometry = volumeManager.geometry()
            it.delegateMaterial = volumeManager.material()
            it.volumeManager = volumeManager
        }
        delegateRenderable = volumeManager.renderable()
        delegateGeometry = volumeManager.geometry()
        delegateMaterial = volumeManager.material()
    }

    override fun createSpatial(): VolumeSpatial {
        return VolumeSpatial(this)
    }

    /**
     * Returns array of slicing plane equations for planes assigned to this volume.
     */
    fun slicingArray(): FloatArray {
        if (slicingPlaneEquations.size > MAX_SUPPORTED_SLICING_PLANES)
            logger.warn("More than ${MAX_SUPPORTED_SLICING_PLANES} slicing planes for ${this.name} set. Ignoring additional planes.")

        val fa = FloatArray(4 * MAX_SUPPORTED_SLICING_PLANES)

        slicingPlaneEquations.entries.take(MAX_SUPPORTED_SLICING_PLANES).forEachIndexed { i, entry ->
            fa[0+i*4] = entry.value.x
            fa[1+i*4] = entry.value.y
            fa[2+i*4] = entry.value.z
            fa[3+i*4] = entry.value.w
        }

        return fa
    }

    /**
     * Goes to the next available timepoint, returning the number of the updated timepoint.
     */
    fun nextTimepoint(): Int {
        return goToTimepoint(viewerState.currentTimepoint + 1)
    }

    /** Goes to the previous available timepoint, returning the number of the updated timepoint. */
    fun previousTimepoint(): Int {
        return goToTimepoint(viewerState.currentTimepoint - 1)
    }

    /** Goes to the [timepoint] given, returning the number of the updated timepoint. */
    open fun goToTimepoint(timepoint: Int): Int {
        val tp = if(timepoint == -1) {
            timepointCount
        } else {
            timepoint
        }
        val current = viewerState.currentTimepoint
        viewerState.currentTimepoint = min(max(tp, 0), timepointCount - 1)
        logger.debug("Going to timepoint ${viewerState.currentTimepoint+1} of $timepointCount")

        if(current != viewerState.currentTimepoint) {
            volumeManager.notifyUpdate(this)
        }

        return viewerState.currentTimepoint
    }

    /**
     * Goes to the last timepoint.
     */
    open fun goToLastTimepoint(): Int {
        return goToTimepoint(-1)
    }

    /**
     * Goes to the first timepoint.
     */
    open fun goToFirstTimepoint(): Int {
        return goToTimepoint(0)
    }

    fun prepareNextFrame() {
        cacheControls.prepareNextFrame()
    }

    /**
     * Returns the local scaling of the volume, taking voxel size and [pixelToWorldRatio] into account.
     */
    open fun localScale(): Vector3f {
        // we are using the first visible source here, which might of course change.
        // TODO: Figure out a better way to do this. It might be an issue for multi-view datasets.

        // TODO: are the voxel sizes determined here really not used?
        // val index = viewerState.visibleSourceIndices.firstOrNull()
        // var voxelSizes: VoxelDimensions = FinalVoxelDimensions("um", 1.0, 1.0, 1.0)
//        if(index != null) {
//            val source = viewerState.sources[index]
//            voxelSizes = source.spimSource.voxelDimensions ?: voxelSizes
//        }

        return Vector3f(
//            voxelSizes.dimension(0).toFloat() * pixelToWorldRatio,
//            voxelSizes.dimension(1).toFloat() * pixelToWorldRatio,
//            voxelSizes.dimension(2).toFloat() * pixelToWorldRatio
            pixelToWorldRatio,
            pixelToWorldRatio,
            pixelToWorldRatio
        )
    }

    /**
     * Samples a point from the currently used volume, [uv] is the texture coordinate of the volume, [0.0, 1.0] for
     * all of the components.
     *
     * Returns the sampled value as a [Float], or null in case nothing could be sampled.
     */
    open fun sample(uv: Vector3f, interpolate: Boolean = true): Float? {
        return null
    }

    /**
     * Takes samples along the ray from [start] to [end] from the currently active volume.
     * Values beyond [0.0, 1.0] for [start] and [end] will be clamped to that interval.
     *
     * Returns the list of samples (which might include `null` values in case a sample failed),
     * as well as the delta used along the ray, or null if the start/end coordinates are invalid.
     */
    open fun sampleRay(start: Vector3f, end: Vector3f): Pair<List<Float?>, Vector3f>? {
        return null
    }

    companion object {
        val setupId = AtomicInteger(0)
        val scifio: SCIFIO = SCIFIO()
        private val logger by LazyLogger()

        @JvmStatic @JvmOverloads fun fromSpimData(
            spimData: SpimDataMinimal,
            hub : Hub,
            options : VolumeViewerOptions = VolumeViewerOptions()
        ): Volume {
            val ds = SpimDataMinimalSource(spimData)
            return Volume(ds, options, hub)
        }

        @JvmStatic @JvmOverloads fun fromXML(
            path: String,
            hub: Hub,
            options : VolumeViewerOptions = VolumeViewerOptions()
        ): Volume {
            val spimData = XmlIoSpimDataMinimal().load(path)
            val ds = SpimDataMinimalSource(spimData)
            return Volume(ds, options, hub)
        }

        @JvmStatic @JvmOverloads fun <T: NumericType<T>> fromRAI(
            img: RandomAccessibleInterval<T>,
            type: T,
            axisOrder: AxisOrder = DEFAULT,
            name: String,
            hub: Hub,
            options: VolumeViewerOptions = VolumeViewerOptions()
        ): Volume {
            val converterSetups: ArrayList<ConverterSetup> = ArrayList()
            val stacks: ArrayList<RandomAccessibleInterval<T>> = AxisOrder.splitInputStackIntoSourceStacks(img, AxisOrder.getAxisOrder(axisOrder, img, false))
            val sourceTransform = AffineTransform3D()
            val sources: ArrayList<SourceAndConverter<T>> = ArrayList()

            var numTimepoints = 1
            for (stack in stacks) {
                val s: Source<T>
                if (stack.numDimensions() > 3) {
                    numTimepoints = stack.max(3).toInt() + 1
                    s = RandomAccessibleIntervalSource4D<T>(stack, type, sourceTransform, name)
                } else {
                    s = RandomAccessibleIntervalSource<T>(stack, type, sourceTransform, name)
                }
                val source: SourceAndConverter<T> = BigDataViewer.wrapWithTransformedSource(
                    SourceAndConverter<T>(s, BigDataViewer.createConverterToARGB(type)))
                converterSetups.add(BigDataViewer.createConverterSetup(source, setupId.getAndIncrement()))
                sources.add(source)
            }

            @Suppress("UNCHECKED_CAST")
            val cacheControl = if (img is VolatileView<*, *>) {
                val viewData: VolatileViewData<T, Volatile<T>> = (img as VolatileView<T, Volatile<T>>).volatileViewData
                viewData.cacheControl
            } else {
                null
            }

            val ds = VolumeDataSource.RAISource<T>(type, sources, converterSetups, numTimepoints, cacheControl)
            return RAIVolume(ds, options, hub)
        }

        @JvmStatic @JvmOverloads fun <T: NumericType<T>> fromSourceAndConverter(
            source: SourceAndConverter<T>,
            type: T,
            name: String,
            hub: Hub,
            options: VolumeViewerOptions = VolumeViewerOptions()
        ): Volume {
            val converterSetups: ArrayList<ConverterSetup> = ArrayList()
            val sources = arrayListOf(source)
            val numTimepoints = 1

            val img = source.spimSource.getSource(0, 0)

            @Suppress("UNCHECKED_CAST")
            val cacheControl = if (img is VolatileView<*, *>) {
                val viewData: VolatileViewData<T, Volatile<T>> = (img as VolatileView<T, Volatile<T>>).volatileViewData
                viewData.cacheControl
            } else {
                null
            }

            val ds = VolumeDataSource.RAISource<T>(type, sources, converterSetups, numTimepoints, cacheControl)
            val volume = RAIVolume(ds, options, hub)
            volume.name = name

            return volume
        }

        @Deprecated("Please use the version that takes List<Timepoint> as input instead of this one.")
        @JvmStatic @JvmOverloads fun <T: NumericType<T>> fromBuffer(
            volumes: LinkedHashMap<String, ByteBuffer>,
            width: Int,
            height: Int,
            depth: Int,
            type: T,
            hub: Hub,
            voxelDimensions: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f),
            voxelUnit: String = "um",
            options: VolumeViewerOptions = VolumeViewerOptions()
        ): BufferedVolume {
            val list = CopyOnWriteArrayList<BufferedVolume.Timepoint>()
            volumes.forEach {
                list.add(BufferedVolume.Timepoint(it.key, it.value))
            }

            return fromBuffer(list, width, height, depth, type, hub, voxelDimensions, voxelUnit, options)
        }

        @JvmStatic @JvmOverloads fun <T: NumericType<T>> fromBuffer(
            volumes: List<BufferedVolume.Timepoint>,
            width: Int,
            height: Int,
            depth: Int,
            type: T,
            hub: Hub,
            voxelDimensions: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f),
            voxelUnit: String = "um",
            options: VolumeViewerOptions = VolumeViewerOptions()
        ): BufferedVolume {
            val converterSetups: ArrayList<ConverterSetup> = ArrayList()
            val sources: ArrayList<SourceAndConverter<T>> = ArrayList()

            val timepoints = CopyOnWriteArrayList<BufferedVolume.Timepoint>(volumes)
            val s = BufferSource(timepoints, width, height, depth, FinalVoxelDimensions(voxelUnit, *(voxelDimensions.map { it.toDouble() }.toDoubleArray())), "", type)
            val source: SourceAndConverter<T> = BigDataViewer.wrapWithTransformedSource(
                    SourceAndConverter<T>(s, BigDataViewer.createConverterToARGB(type)))
           converterSetups.add(BigDataViewer.createConverterSetup(source, setupId.getAndIncrement()))
           sources.add(source)

            val ds = VolumeDataSource.RAISource<T>(type, sources, converterSetups, volumes.size)
            return BufferedVolume(ds, options, hub)
        }

        /**
         * Generates a procedural volume based on the open simplex noise algorithm, with [size]^3 voxels.
         * [radius] sets the blob radius, while [shift] can be used to move through the continuous noise
         * volume, essentially offsetting the volume by the value given. [intoBuffer] can be used to
         * funnel the data into a pre-existing buffer, otherwise one will be allocated. [seed] can be
         * used to choose a seed for the PRNG.
         *
         * Returns the newly-allocated [ByteBuffer], or the one given in [intoBuffer], set to position 0.
         */
        @JvmStatic fun generateProceduralVolume(size: Long, radius: Float = 0.0f,
                                                seed: Long = Random.randomFromRange(0.0f, 133333337.0f).toLong(),
                                                shift: Vector3f = Vector3f(0.0f),
                                                intoBuffer: ByteBuffer? = null, use16bit: Boolean = false): ByteBuffer {
            val f = 3.0f / size
            val center = size / 2.0f + 0.5f
            val noise = OpenSimplexNoise(seed)
            val (range, bytesPerVoxel) = if(use16bit) {
                65535 to 2
            } else {
                255 to 1
            }
            val byteSize = (size*size*size*bytesPerVoxel).toInt()

            val buffer = intoBuffer ?: MemoryUtil.memAlloc(byteSize * bytesPerVoxel)

//            (0 until byteSize/bytesPerVoxel).chunked(byteSize/4).forEachParallel { subList ->
            (0 until byteSize/bytesPerVoxel).forEach {
//                subList.forEach {
                    val x = it.rem(size)
                    val y = (it / size).rem(size)
                    val z = it / (size * size)

                    val dx = center - x
                    val dy = center - y
                    val dz = center - z

                    val offset = abs(noise.random3D((x + shift.x()) * f, (y + shift.y()) * f, (z + shift.z()) * f))
                    val d = sqrt(dx * dx + dy * dy + dz * dz) / size

                    val result = if(radius > Math.ulp(1.0f)) {
                        if(d - offset < radius) { ((d-offset)*range).toInt().toShort() } else { 0 }
                    } else {
                        ((d - offset) * range).toInt().toShort()
                    }

                    if(use16bit) {
                        buffer.asShortBuffer().put(it, result)
                    } else {
                        buffer.put(it, result.toByte())
                    }
//                }
            }

            return buffer
        }

        /**
         * Reads a volume from the given [file].
         */
        @JvmStatic fun fromPath(file: Path, hub: Hub): BufferedVolume {
            if(file.normalize().toString().endsWith("raw")) {
                return fromPathRaw(file, hub)
            }

            val id = file.fileName.toString()

            val reader = scifio.initializer().initializeReader(FileLocation(file.toFile()))

            val dims = Vector3i()
            with(reader.openPlane(0, 0)) {
                dims.x = lengths[0].toInt()
                dims.y = lengths[1].toInt()
                dims.z = reader.getPlaneCount(0).toInt()
            }

            val bytesPerVoxel = reader.openPlane(0, 0).imageMetadata.bitsPerPixel/8
            reader.openPlane(0, 0).imageMetadata.pixelType

            val type: NumericType<*> = when(reader.openPlane(0, 0).imageMetadata.pixelType) {
                FormatTools.INT8 -> ByteType()
                FormatTools.INT16 -> ShortType()
                FormatTools.INT32 -> IntType()

                FormatTools.UINT8 -> UnsignedByteType()
                FormatTools.UINT16 -> UnsignedShortType()
                FormatTools.UINT32 -> UnsignedIntType()

                FormatTools.FLOAT -> FloatType()

                else -> {
                    logger.error("Unknown scif.io pixel type ${reader.openPlane(0, 0).imageMetadata.pixelType}, assuming unsigned byte.")
                    UnsignedByteType()
                }
            }

            logger.debug("Loading $id from disk")
            val imageData: ByteBuffer = MemoryUtil.memAlloc((bytesPerVoxel * dims.x * dims.y * dims.z))

            logger.debug("${file.fileName}: Allocated ${imageData.capacity()} bytes for $type ${8*bytesPerVoxel}bit image of $dims")

            val start = System.nanoTime()

//            if(reader.openPlane(0, 0).imageMetadata.isLittleEndian) {
            logger.debug("Volume is little endian")
            (0 until reader.getPlaneCount(0)).forEach { plane ->
                imageData.put(reader.openPlane(0, plane).bytes)
            }
//            } else {
//                logger.info("Volume is big endian")
//                (0 until reader.getPlaneCount(0)).forEach { plane ->
//                    imageData.put(swapEndianUnsafe(reader.openPlane(0, plane).bytes))
//                }
//            }

            val duration = (System.nanoTime() - start) / 10e5
            logger.debug("Reading took $duration ms")

            imageData.flip()

            val volumes = CopyOnWriteArrayList<BufferedVolume.Timepoint>()
            volumes.add(BufferedVolume.Timepoint(id, imageData))
            // TODO: Kotlin compiler issue, see https://youtrack.jetbrains.com/issue/KT-37955
            return when(type) {
                is ByteType -> fromBuffer(volumes, dims.x, dims.y, dims.z, ByteType(), hub)
                is UnsignedByteType -> fromBuffer(volumes, dims.x, dims.y, dims.z, UnsignedByteType(), hub)
                is ShortType -> fromBuffer(volumes, dims.x, dims.y, dims.z, ShortType(), hub)
                is UnsignedShortType -> fromBuffer(volumes, dims.x, dims.y, dims.z, UnsignedShortType(), hub)
                is IntType -> fromBuffer(volumes, dims.x, dims.y, dims.z, IntType(), hub)
                is UnsignedIntType -> fromBuffer(volumes, dims.x, dims.y, dims.z, UnsignedIntType(), hub)
                is FloatType -> fromBuffer(volumes, dims.x, dims.y, dims.z, FloatType(), hub)
                else -> throw UnsupportedOperationException("Image type ${type.javaClass.simpleName} not supported for volume data.")
            }
        }

        /**
         * Reads raw volumetric data from a [file].
         *
         * Returns the new volume.
         */
        @JvmStatic fun fromPathRaw(file: Path, hub: Hub): BufferedVolume {

            val infoFile: Path
            val volumeFiles: List<Path>

            if(Files.isDirectory(file)) {
                volumeFiles = Files.list(file).filter { it.toString().endsWith(".raw") && Files.isRegularFile(it) && Files.isReadable(it) }.toList()
                infoFile = file.resolve("stacks.info")
            } else {
                volumeFiles = listOf(file)
                infoFile = file.resolveSibling("stacks.info")
            }

            val lines = Files.lines(infoFile).toList()

            logger.debug("reading stacks.info (${lines.joinToString()}) (${lines.size} lines)")
            val dimensions = Vector3i(lines.get(0).split(",").map { it.toInt() }.toIntArray())
            logger.debug("setting dim to ${dimensions.x}/${dimensions.y}/${dimensions.z}")
            logger.debug("Got ${volumeFiles.size} volumes")

            val volumes = CopyOnWriteArrayList<BufferedVolume.Timepoint>()
            volumeFiles.forEach { v ->
                val id = v.fileName.toString()
                val buffer: ByteBuffer by lazy {

                    logger.debug("Loading $id from disk")
                    val buffer = ByteArray(1024 * 1024)
                    val stream = FileInputStream(v.toFile())
                    val imageData: ByteBuffer = MemoryUtil.memAlloc((2 * dimensions.x * dimensions.y * dimensions.z))

                    logger.debug("${v.fileName}: Allocated ${imageData.capacity()} bytes for UINT16 image of $dimensions")

                    val start = System.nanoTime()
                    var bytesRead = stream.read(buffer, 0, buffer.size)
                    while (bytesRead > -1) {
                        imageData.put(buffer, 0, bytesRead)
                        bytesRead = stream.read(buffer, 0, buffer.size)
                    }
                    val duration = (System.nanoTime() - start) / 10e5
                    logger.debug("Reading took $duration ms")

                    imageData.flip()
                    imageData
                }

                volumes.add(BufferedVolume.Timepoint(id, buffer))
            }

            return fromBuffer(volumes, dimensions.x, dimensions.y, dimensions.z, UnsignedShortType(), hub)
        }

        /** Amount of supported slicing planes per volume, see also sampling shader segments */
        private const val MAX_SUPPORTED_SLICING_PLANES = 16

    }

    open class VolumeSpatial(val volume: Volume): DefaultSpatial(volume) {
        /**
         * Composes the world matrix for this volume node, taken voxel size and [pixelToWorldRatio]
         * into account.
         */
        override fun composeModel() {
            @Suppress("SENSELESS_COMPARISON")
            if(position != null && rotation != null && scale != null) {
                model.translation(position)
                model.mul(Matrix4f().set(this.rotation))
                if(volume.origin == Origin.Center) {
                    model.translate(-2.0f, -2.0f, -2.0f)
                }
                model.scale(scale)
                model.scale(volume.localScale())
            }
        }
    }
}
