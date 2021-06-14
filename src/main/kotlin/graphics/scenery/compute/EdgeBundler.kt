package graphics.scenery.compute

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.primitives.Line
import graphics.scenery.primitives.LinePair
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.LazyLogger
import org.jocl.cl_mem
import java.io.File
import java.lang.Float.MAX_VALUE
import java.nio.*

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.max

/**
 * Represents a 3D coordinated and optional attributes (e.g. a measured signal, time, ...)
 * TODO: so far the attributes are not used, but for future releases it would be possible to pass them as vertex
 * attributes and use them for color mapping
 *
 * @author Johannes Waschke <jowaschke@cbs.mpg.de>
 *
 * @property x Spatial position, x coordinate
 * @property y Spatial position, y coordinate
 * @property z Spatial position, z coordinate
 */
class PointWithMeta(var x: Float = 0.0f, var y: Float = 0.0f, var z: Float = 0.0f) {
    private var attributes: ArrayList<Float> = ArrayList()

    /**
     * Adds a new attribute. Note, attributes are always stored as Floats. Besides that, consider that all points should
     * have the same number (and order) of attributes!
     * @param v Numerical value of an attribute (e.g. a time point, measurement,...)
     */
    fun addAttribute(v: Float) {
        this.attributes.add(v)
    }

    /**
     * Element-wise addition of two such objects. This includes their attributes.
     * (A use case for application of this function is averaging/interpolation of multiple points.)
     * @param v Another point to be added on this
     * @return A new object containing the sum of this one and [v]
     */
    operator fun plus(v: PointWithMeta): PointWithMeta
    {
        val newPoint = PointWithMeta(this.x + v.x, this.y + v.y, this.z + v.z)
        for(i in 0 until this.attributes.size)
        {
            newPoint.addAttribute(this.attributes[i] + v.attributes[i])
        }
        return newPoint
    }

    /**
     * Multiplication of x/y/z and attributes with a scalar value.
     * (A use case for application of this function is averaging/interpolation of multiple points.)
     * @param v A scalar to be multiplied with this point's coordinates and attributes
     * @return A new object with the result
     */
    operator fun times(v: Float): PointWithMeta
    {
        val newPoint = PointWithMeta(this.x * v, this.y * v, this.z * v)
        for(i in 0 until this.attributes.size)
        {
            newPoint.addAttribute(this.attributes[i] * v)
        }
        return newPoint
    }

    /**
     * Simple Euclidian distance measure
     * @param v Another point to which the distance should be calculated
     * @return The distance
     */
    fun distanceTo(v: PointWithMeta): Float
    {
        return sqrt( (this.x - v.x).pow(2) + (this.y - v.y).pow(2) + (this.z - v.z).pow(2))
    }

    /**
     * Provide a copy of this object
     * @return a copy of this object
     */
    fun clone(): PointWithMeta
    {
        return this * 1.0f // TODO: is this too hacky?
    }
}

/**
 * Class to prepare data, perform edge bundling in OpenCL, deliver the results
 *
 * Important are especially
 * - [numberOfClusters]. The more clusters, the lower is the (quadratic) runtime per "data piece".
 * - [bundlingRadius]. The distance in which magnetic forces work. Should be something like 3% of the data width
 *
 * @author Johannes Waschke <jowaschke@cbs.mpg.de>
 */
class EdgeBundler(override var hub: Hub?): Hubable {
    private val logger by LazyLogger()

    init {
        hub?.add(this)
    }

    /**
     *  Creates lines from a folder full of csv files. Each file is a track, each line
     *  must look like "x,y,z[,attribute1[,attribute2[, ...]]]"
     *  @param csvPath The path to a folder of CSV files
     */
    constructor(csvPath: String, hub: Hub?) : this(hub) {
        logger.debug("Create EdgeBundler with csv path")
        loadTrajectoriesFromCSV(csvPath)
        estimateGoodParameters()
    }

    /**
     * Prepares the EdgeBundler from a given set of lines
     * @param lines A set of lines
     */
    constructor(lines: List<Line>, hub: Hub?) : this(hub) {
        logger.debug("Create EdgeBundler with line set, number of lines:" + lines.size.toString())
        loadTrajectoriesFromLines(lines)
        estimateGoodParameters()
    }

    private var trackSetOriginal: Array<Array<PointWithMeta>> = Array(0) {Array(0) { PointWithMeta() }}
    private var trackSetBundled: Array<Array<PointWithMeta>> = Array(0) {Array(0) { PointWithMeta() }}

    // Min/max of data, from these we derive a proposal for paramBundlingRadius
    private var minX: Float = Float.MAX_VALUE
    private var minY: Float = Float.MAX_VALUE
    private var minZ: Float = Float.MAX_VALUE
    private var maxX: Float = Float.MIN_VALUE
    private var maxY: Float = Float.MIN_VALUE
    private var maxZ: Float = Float.MIN_VALUE

    private var resultClustersReverse: Array<Int> = arrayOf<Int>()
    private var resultClusters: Array<ArrayList<Int>> = arrayOf()

    // Arrays for containing the data flattened to basic Ints/Floats
    private var oclPoints = arrayListOf<Float>()
    private var oclPointToTrackIndices = arrayListOf<Int>()
    private var oclPointsResult = arrayListOf<Float>()
    private var oclTrackStarts = arrayListOf<Int>()
    private var oclTrackLengths = arrayListOf<Int>()
    private var oclClusterIndices = arrayListOf<Int>()
    private var oclClusterInverse = arrayListOf<Int>()
    private var oclClusterStarts = arrayListOf<Int>()
    private var oclClusterLengths = arrayListOf<Int>()

    /** Length of streamlines for edge bundling (and the result) */
    var resampleTo = 30
    /** Number of clusters during edge bundling. More are quicker. */
    var numberOfClusters = 1
    /** Length of the reference track for edge bundling. */
    var clusteringTrackSize = 6
    /** Iterations for defining the clusters. */
    var clusteringIterations = 20
    /** Iterations for edge bundling. Each iteration includes one smoothing step! Hence, for more iterations, reduce smoothing. */
    var bundlingIterations = 10
    /** Radius in which magnet forces apply. Should be something link 2% of data space width */
    var bundlingRadius: Float = 10.0f
    /** Length of "magnet step". Just 1.0 is fine. Small steps require more iterations, larger might step too far. */
    var bundlingStepsize: Float = 1.0f
    /** TODO currently unused; it's a curvature threshold, but likely not really helpful */
    var bundlingAngleMin: Float = 0.0f
    /** Defines how much non-parallel tracks stick together. */
    var bundlingAngleStick: Float = 0.8f
    /** Divides the calculation into pieces of this size */
    var bundlingChunkSize: Int = 10000
    /** 1 If lines should be bundled up to the last point; 0 if endpoints should stay at original position */
    var bundlingIncludeEndpoints: Int = 0
    /** Number of neighbors being considered by the smoothing window */
    var bundlingSmoothingRadius: Int = 1
    /** Degree how much to mix the smoothed result with the unsmooth data (1 = full smooth), 0.5 = 50:50) */
    var bundlingSmoothingIntensity: Float = 0.5f
    /** Opacity of the lines while rendering */
    var paramAlpha: Float = 0.01f

    /**
     * Create a basic, empty line with the opacity defined by [paramAlpha] and a color from the [colorMap], depending
     * on its [trackId] and the corresponding cluster.
     * @param trackId The ID of the track, but currently only used for naming the line
     * @return And empty line with basic properties
     */
    private fun makeLine(trackId: Int): Line {
        val line = Line(transparent = true, simple = false)
        line.name = trackId.toString()
        line.material {
            blending.opacity = paramAlpha
        }
        line.spatial {
            position = Vector3f(0.0f, 0.0f, 0.0f)
        }
        line.edgeWidth = 0.01f
        return line
    }

    /**
     * Create a basic, empty LinePair with the opacity defined by [paramAlpha] and a color from the [colorMap],
     * depending on its [trackId] and the corresponding cluster.
     * @param trackId The ID of the track, but currently only used for naming the line
     * @return And empty LinePair with basic properties
     */
    private fun makeLinePair(trackId: Int): LinePair {
        val line = LinePair(transparent = true)
        line.name = trackId.toString()
        line.material {
            blending.opacity = paramAlpha
            depthTest = Material.DepthTest.Always
        }
        line.spatial {
            position = Vector3f(0.0f, 0.0f, 0.0f)
        }
        line.edgeWidth = 0.01f
        return line
    }

    /**
     * This function is quite important. We have to do a lot of calculations and depending on the operating system
     * (Did someone say "windows" ...hmmm?!), an OpenCL calculation is canceled by a watchdog, e.g. after 1-2s. This is
     * of course very annoying. Especially since the error output is usually only "CL_OUT_OF_RESOURCES" - not helpful!
     * However, to avoid trouble, the calculation pool (which contains one process per point) is splitted into chunks.
     * A chunk size of 1000 means that 1000 points are processed in one rush, then a short break is made, and then the
     * next 1000 points are considered, and so on. Naturally, this likely leads to a lot of overhead. So the right
     * chunk size should be chosen in terms of keeping calculation time lower than the watchdog's kill time.
     *
     * TL;DR: make chunk size as big as possible (e.g. 100.000-1.000.000) but if it crashes, make it smaller.
     * E.g. a quite weak MX150 graphics card only works with 10.000 or something like that.
     *
     * By the way, if nothing works, consider an increase of the number of clusters. This also dramatically decreases
     * calculation time per point.
     *
     * @param elementsPerCalculation Number of elements that OpenCL should handle at once
     */
    fun setChunkSize(elementsPerCalculation:Int) {
        bundlingChunkSize = elementsPerCalculation
    }

    /**
     * Get the results as array of lines (=only bundled lines!)
     * @return The bundled lines
     */
    fun getLines(): Array<Line> {
        val lines = Array<Line>(trackSetBundled.size) { Line() }
        for(t in trackSetBundled.indices) {
            // The next lines show the "boring" way [for the smarter one, see below]:
            lines[t] = makeLine(t)
            val vertices = List<Vector3f>(trackSetBundled[t].size) { i -> Vector3f(trackSetBundled[t][i].x, trackSetBundled[t][i].y, trackSetBundled[t][i].z)}
            lines[t].addPoints(vertices)
        }
        return lines
    }

    /**
     * Get the results as array of LinePairs (=original and bundled lines combined)
     * @return The array of LinePairs
     */
    fun getLinePairs(): Array<LinePair> {
        val lines = Array<LinePair>(trackSetBundled.size) { LinePair() }
        for(t in trackSetBundled.indices) {
            // The next lines show the "boring" way [for the smarter one, see below]:
            lines[t] = makeLinePair(t)
            val verticesOriginal = Array<Vector3f>(trackSetOriginal[t].size) { i ->
                Vector3f(trackSetOriginal[t][i].x, trackSetOriginal[t][i].y, trackSetOriginal[t][i].z)}
            val verticesBundled = Array<Vector3f>(trackSetBundled[t].size) { i ->
                Vector3f(trackSetBundled[t][i].x, trackSetBundled[t][i].y, trackSetBundled[t][i].z)}
            lines[t].addPointPairs(verticesOriginal, verticesBundled)
            if(logger.isDebugEnabled) {
                for (i in trackSetBundled[t].indices) {
                    logger.debug("Line $i: ${(trackSetBundled[t][i].x - trackSetOriginal[t][i].x)}")
                }
            }
        }
        return lines
    }

    /**
     * Convenience function to return both line pairs and clusters.
     */
    fun getLinePairsAndClusters() = getLinePairs().zip(getClusterOfTracks())

    /**
     * Some parameters must be set up according to data properties. We try some basic guessing here.
     * This basically calculates some percent of the maximum width of the data's bounding box for bundling radius.
     * The number of clusters is simply chosen to have an average of 500 items per cluster.
     * The results are stored in the members of this class (and can be overwritten afterwards, if needed)
     */
    fun estimateGoodParameters() {
        bundlingRadius = max(maxX - minX, max(maxY - minY, maxZ - minZ)) * 0.03f // 3% of data dimension
        numberOfClusters = ceil(trackSetBundled.size.toFloat() / 500.0f).toInt() // an average of 500 lines per cluster
        logger.debug("Divide the data into $numberOfClusters clusters, magnetic forces over a distance of $bundlingRadius")
    }

    /**
     * Find the clusterId of a specific track
     * @param trackIndex The index of the track
     * @return The index of the track's cluster
     */
    fun getClusterOfTrack(trackIndex: Int): Int {
        return resultClustersReverse[trackIndex]
    }

    /**
     * Get the whole mapping of trackId to clusterId
     * @return Array where the trackId^th position contains its respective clusterId
     */
    fun getClusterOfTracks(): Array<Int> {
        return resultClustersReverse
    }

    /**
     * Creates basic Int/Float-Arrays from the hierarchical structures (e.g. the cluster-track relationship) and 3D
     * data points. The results are stored internally.
     */
    private fun prepareFlattenedData() {
        oclPoints.clear()
        oclPointToTrackIndices.clear()
        oclPointsResult.clear()
        oclTrackStarts.clear()
        oclTrackLengths.clear()
        oclClusterInverse.clear()
        oclClusterIndices.clear()
        oclClusterStarts.clear()
        oclClusterLengths.clear()

        var pointCounter: Int = 0
        for(t in trackSetBundled.indices) {
            oclTrackStarts.add(pointCounter)
            oclTrackLengths.add(trackSetBundled[t].size)
            for(p in trackSetBundled[t].indices) {
                oclPoints.addAll(arrayOf(trackSetBundled[t][p].x, trackSetBundled[t][p].y, trackSetBundled[t][p].z, 0.0f))
                oclPointToTrackIndices.add(t)
                oclPointsResult.addAll(arrayOf(trackSetBundled[t][p].x, trackSetBundled[t][p].y, trackSetBundled[t][p].z, 0.0f))
                pointCounter++
            }
            oclClusterInverse.add(0) // TODO I just want to prepare a list with n elements. I think there is a (much) better way
        }

        var trackCounter: Int = 0
        for(c in resultClusters.indices) {
            oclClusterStarts.add(trackCounter)
            oclClusterLengths.add(resultClusters[c].size)
            for(t in 0 until resultClusters[c].size) {
                oclClusterIndices.add(resultClusters[c][t])
                oclClusterInverse[resultClusters[c][t]] = c
            }
            trackCounter += resultClusters[c].size
        }
    }

    /**
     * Debug: print buffer contents to console/logger
     * @param b The buffer object
     */
    private fun printIntBuffer(b: IntBuffer) {
        val b2: IntBuffer = b.duplicate()
        b2.rewind()
        while(b2.hasRemaining()) {
            print(b2.get().toString() + ",")
        }
        println()
    }

    /**
     * Debug: print buffer contents to console/logger
     * @param b The buffer object
     */
    private fun printFloatBuffer(b: FloatBuffer) {
        val b2: FloatBuffer = b.duplicate()
        b2.rewind()
        var counter = 0
        while(b2.hasRemaining()) {
            counter++
            val x = b2.get().toString()
            if(counter <= 10000)
                print(x + ", ")
        }
        println()
        if(counter > 10000) println("  [Stopped output; Total number of elements in buffer: " + counter + "]")
    }

    /**
     * Create a new IntBuffer from an array of Ints
     * TODO Actually there is no real need for this function anymore; I used to perform additional processing
     * @param values The integer values
     * @return Buffer with these values
     */
    private fun createIntBuffer(values: ArrayList<Int>): IntBuffer {
        return IntBuffer.wrap(values.toIntArray())
    }

    /**
     * Create a new FloatBuffer from an array of Floats
     * TODO Actually there is no real need for this function anymore; I used to perform additional processing
     * @param values The float values
     * @return Buffer with these values
     */
    private fun createFloatBuffer(values: ArrayList<Float>): FloatBuffer {
        return FloatBuffer.wrap(values.toFloatArray())
    }

    /**
     * Create work packages according to the chosen [bundlingChunkSize]. For 25.000 tracks and a chunk size of
     * 10.000 this function would return [10.000, 10.000, 5.000].
     * @return An array of numbers saying how many elements should be handled in each OpenCL call
     */
    private fun getChunkSizes(): Array<Int> {
        val num: Int = oclPoints.size / bundlingChunkSize
        val remainder: Int =  oclPoints.size % bundlingChunkSize
        val chunkSizes: Array<Int> = Array(num + 1) { _ -> bundlingChunkSize}
        chunkSizes[num] = remainder
        return chunkSizes
    }


    /**
     * Helper to read values and copy them somewhere else (within in the OpenCL context)
     * @param openCLContext The OpenCL context
     * @param memResult The memory object holding the result that should be read
     * @param memNew The memory object that needs to be updated with the new values (read from [memResult])
     * @param buffer The buffer object in which the values from [memResult] should be stored as well
     */
    private fun copyResultHelper(openCLContext: OpenCLContext,
                                 memResult: cl_mem,
                                 memNew: cl_mem,
                                 buffer: FloatBuffer) {
        openCLContext.readBuffer(memResult, buffer)
        buffer.rewind()
        openCLContext.writeBuffer(buffer, memNew)
        buffer.rewind()
    }

    /**
     * The whole OpenCL-pipeline. Converting the data, create buffers, perform the calculation multiple times (according
     * to [bundlingIterations], and splitted into chunks according to [bundlingChunkSize]). For each
     * iteration, edge bundling is performed first and smoothing is performed afterwards.
     * @return True if everythin went well, false if note
     */
    fun runEdgeBundling(): Boolean {
        prepareFlattenedData()

        val ocl: OpenCLContext? = try {
            hub?.get<OpenCLContext>() ?: OpenCLContext(hub)
        } catch (e: Exception) {
            logger.warn("Exception during OpenCL initialisation: $e")
            e.printStackTrace()
            null
        }

        if (ocl == null) {
            logger.warn("Could not create OpenCL compute context -- Do you have the necessary OpenCL libraries installed?")
            return false
        } else {
            // Create buffers based on the flattened data arrays.
            // Note: the result array must be initialized with the input data at first, since some points (e.g.
            // the endpoints of lines) are not processed at all and their values are not copied automatically. Thus,
            // to avoid missing/empty data, we create the result buffer filled with the original line data.
            val oclPointsInAndOut = createFloatBuffer(oclPoints)
            val points: cl_mem = ocl.wrapInput(oclPointsInAndOut, true)
            val pointsResult: cl_mem = ocl.wrapInput(oclPointsInAndOut, false)
            val pointToTrackIndices: cl_mem = ocl.wrapInput(createIntBuffer(oclPointToTrackIndices), true)
            val trackStarts: cl_mem = ocl.wrapInput(createIntBuffer(oclTrackStarts), true)
            val trackLengths: cl_mem = ocl.wrapInput(createIntBuffer(oclTrackLengths), true)
            val clusterStarts: cl_mem = ocl.wrapInput(createIntBuffer(oclClusterStarts), true)
            val clusterLengths: cl_mem = ocl.wrapInput(createIntBuffer(oclClusterLengths), true)
            val clusterIndices: cl_mem = ocl.wrapInput(createIntBuffer(oclClusterIndices), true)
            val clusterInverse: cl_mem = ocl.wrapInput(createIntBuffer(oclClusterInverse), true)

            // Get kernels for edge bundling and smoothing
            ocl.loadKernel(EdgeBundler::class.java.getResource("EdgeBundler.cl"), "edgeBundling")
            ocl.loadKernel(EdgeBundler::class.java.getResource("EdgeBundler.cl"), "smooth")
            val chunkSizes = getChunkSizes()

            logger.info("Starting OpenCL edge bundling of ${oclPoints.size} points (${trackSetBundled.size} tracks)")

            val pointSize = oclPoints.size/4
            var statusCounter = 0
            val totalCounter = 2 * bundlingIterations * chunkSizes.size
            for(i in 0 until bundlingIterations) {
                for(c in chunkSizes.indices) {
                    statusPrint(++statusCounter, totalCounter) // Current status; Will be called paramBundlingIterations * chunksizes.size times

                    val offset = c * bundlingChunkSize
                    ocl.runKernel("edgeBundling", chunkSizes[c],
                        trackStarts,
                        trackLengths,
                        clusterStarts,
                        clusterLengths,
                        clusterIndices,
                        clusterInverse,
                        points,
                        pointsResult,
                        pointToTrackIndices,
                        pointSize,
                        bundlingRadius,
                        bundlingStepsize,
                        bundlingAngleMin,
                        bundlingAngleStick,
                        offset,
                        bundlingIncludeEndpoints)
                }
                copyResultHelper(ocl, pointsResult, points, oclPointsInAndOut)

                for(c in chunkSizes.indices) {
                    statusPrint(++statusCounter, totalCounter)
                    val offset = c * bundlingChunkSize

                    ocl.runKernel("smooth", chunkSizes[c],
                        trackStarts,
                        trackLengths,
                        points,
                        pointsResult,
                        pointToTrackIndices,
                        pointSize,
                        bundlingSmoothingRadius,
                        bundlingSmoothingIntensity,
                        offset)
                }
                copyResultHelper(ocl, pointsResult, points, oclPointsInAndOut)
            }
            if(logger.isDebugEnabled) {
                printFloatBuffer(oclPointsInAndOut)
            }
            processOpenCLResult(oclPointsInAndOut)
            logger.info("Finished OpenCL edge bundling.")
        }
        return true
    }

    /**
     * Read the data from the (flat) buffer and write it into an array of arrays of points.
     */
    private fun processOpenCLResult(buffer: FloatBuffer) {
        val b = buffer.duplicate()
        b.rewind()
        var posCounter = 0
        var localCounter = 0
        var trackCounter = 0

        while(b.hasRemaining()) {
            val x = b.get()
            val y = b.get()
            val z = b.get()
            b.get() // The fourth dim is (currently) 0, we throw it away

            trackSetBundled[trackCounter][localCounter].x = x
            trackSetBundled[trackCounter][localCounter].y = y
            trackSetBundled[trackCounter][localCounter].z = z
            posCounter++
            localCounter++
            if(localCounter >= trackSetBundled[trackCounter].size) {
                trackCounter++
                localCounter = 0
            }
        }
    }

    /**
     * Updates the bounding box
     * @param point A new point being added to the bounding box
     */
    private fun updateMinMax(point: PointWithMeta) {
        minX = min(minX, point.x)
        minY = min(minY, point.y)
        minZ = min(minZ, point.z)
        maxX = max(maxX, point.x)
        maxY = max(maxY, point.y)
        maxZ = max(maxZ, point.z)
    }

    /**
     * Look for csv files in [path]. Each file should contain exactly one track. The values are comma-separated. The
     * first three columns are x/y/z respectively. Every additional column is stored as attribute, but currently not
     * further processed/used. The loaded data is stored in a class member. Furthermore, the output data structure
     * is created.
     * @param path Path to the folder containing CSV files
     */
    private fun loadTrajectoriesFromCSV(path: String) {
        val trackSetTemp: ArrayList<Array<PointWithMeta>> = ArrayList()
        File(path).walkBottomUp().forEach {file ->
            if(file.absoluteFile.extension.toLowerCase() == "csv") {
                if (file.absoluteFile.exists()) {
                    val trackTemp = arrayListOf<PointWithMeta>()
                    file.absoluteFile.forEachLine {line ->
                        val entry = line.split(",")
                        val point = PointWithMeta(entry[0].toFloat(), entry[1].toFloat(), entry[2].toFloat())
                        updateMinMax(point)

                        // Use every column behind the third one as attribute (TODO: however, so far we don't use the attributes)
                        for(i in 3 until entry.size)
                        {
                            point.addAttribute(entry[i].toFloat())
                        }
                        trackTemp.add(point)
                    }
                    val track = Array<PointWithMeta>(trackTemp.size) { i -> trackTemp[i]}
                    trackSetTemp.add(track)
                }
            }
        }
        this.trackSetBundled = Array(trackSetTemp.size) {i -> trackSetTemp[i]}
        this.trackSetBundled = resampleTracks(trackSetBundled, resampleTo)
        this.trackSetOriginal = resampleTracks(trackSetBundled, resampleTo) // TODO I don't know a good way for deep copy
    }

    /**
     * Load line data from a set of Line objects. The loaded data is stored in a class member. Furthermore, the output
     * data structure is created.
     * @param lines Array of Line objects
     */
    private fun loadTrajectoriesFromLines(lines: List<Line>) {
        val trackSetTemp: ArrayList<Array<PointWithMeta>> = ArrayList()
        lines.forEach { line ->
            line.geometry {
                val track = Array<PointWithMeta>(vertices.limit() / 3) { _ -> PointWithMeta()}
                vertices.rewind()
                var i = 0
                while(vertices.hasRemaining()) {
                    val point = PointWithMeta(vertices.get(),
                        vertices.get(),
                        vertices.get())
                    updateMinMax(point)

                    track[i] = point
                    i++
                }
                trackSetTemp.add(track)
            }
        }

        this.trackSetBundled = Array(trackSetTemp.size) {i -> trackSetTemp[i]}
        this.trackSetBundled = resampleTracks(trackSetBundled, resampleTo)
        this.trackSetOriginal = resampleTracks(trackSetBundled, resampleTo) // TODO I don't know a good way for deep copy
    }

    /**
     * Triggers the major calculation steps
     */
    fun calculate() {
        quickBundles()
        runEdgeBundling()
    }

    /**
     * Resizes tracks to a fixed size. Needed for Quickbundles, since it requires a fixed-length reference object.
     * The function is also useful for downsampling tracks in order to speed up the calculation.
     * @param tracks The line data
     * @param numElements The number of elements that each track should have after resampling (must be > 2, otherwise no resampling)
     * @return New, resampled tracks (or the original tracks, if [numElements] was < 2)
     */
    private fun resampleTracks(tracks: Array<Array<PointWithMeta>>, numElements: Int): Array<Array<PointWithMeta>> {
        if(numElements < 2) {
            return tracks // Do nothing if it doesn't make sense
        }

        val result: Array<Array<PointWithMeta>> = Array(tracks.size) {Array(0,{ PointWithMeta() })}
        for(i in tracks.indices) {
            result[i] = resampleTrack(tracks[i], numElements)
        }

        return result
    }

    /**
     * Resamples a single track by linear interpolation of new positions between two original positions.
     * @param track A single track
     * @param numElements The intended number of elements for this track
     * @return Resampled track
     */
    private fun resampleTrack(track: Array<PointWithMeta>, numElements: Int): Array<PointWithMeta> {
        val trackOut: Array<PointWithMeta> = Array(numElements) { PointWithMeta() }
        trackOut[0] = track[0]
        val scale = track.size.toFloat() / numElements.toFloat()
        for(i in 1 until numElements) {
            val iScale = i.toFloat() * scale
            val lower: Int = floor(iScale).toInt()
            val upper: Int = ceil(iScale).toInt()
            val ratio = iScale - lower.toFloat()
            val result = track[lower] * (1.0f - ratio) + track[upper] * ratio
            trackOut[i] = result
        }
        trackOut[numElements - 1] = track[track.size - 1]
        return trackOut
    }

    /**
     * Calculates a set of averaged tracks (each of size [clusteringTrackSize]), used for track comparison in
     * Quickbundles.
     * Provide [clustersReverse] array filled with |[tracks]| times 0 to average all tracks (= provide one big cluster)
     * @param tracks The set of tracks that should be averaged
     * @param clustersReverse The cluster structure. Averaging happens only within the same clusters.
     * @return A set of mean tracks, one for each cluster
     */
    private fun calculateMeanTracks(tracks: Array<Array<PointWithMeta>>,
                                    clustersReverse:  Array<Int>): Array<Array<PointWithMeta>> {
        // We create a list of reference tracks for each cluster. The tracks have a fixed number of elements and their
        // positions, so far, are 0/0/0. Furthermore, we create a counter. With the counter we can update a mean track
        // without calculating the mean based on all tracks, but just by adding 1/nth of the next track. This allows
        // clustering in linear time (Quickbundles, Garyfallidis 2012)
        val meanTracks: Array<Array<PointWithMeta>> = Array(numberOfClusters, {Array(clusteringTrackSize, { PointWithMeta() })})
        val meanTrackCounter: Array<Int> = Array(numberOfClusters, {0})

        // Now add all the tracks to the mean track of their respective cluster
        for(i in tracks.indices) {
            meanTrackCounter[clustersReverse[i]] += 1
            val ratio = 1.0f / meanTrackCounter[clustersReverse[i]].toInt()
            for(j in 0 until clusteringTrackSize) {
                meanTracks[clustersReverse[i]][j] = meanTracks[clustersReverse[i]][j] * (1.0f - ratio) + tracks[i][j] * ratio
            }
        }

        return meanTracks
    }

    /**
     * Sum of distances between the points of two tracks (pairwise comparison of ith point with ith point)
     * @param t1 Track 1
     * @param t2 Track 2
     * @return Sum of pairwise distances
     */
    private fun distanceBetweenTracks(t1: Array<PointWithMeta>, t2: Array<PointWithMeta>): Float {
        var dist = 0.0f
        for(i in t2.indices) {
            dist += t1[i].distanceTo(t2[i])
        }
        return dist
    }

    /**
     * Simple status print
     * @param i The number of already performed operations
     * @param total The expected number of operations
     * @param printEveryN If not updates should be printed only after n performed operations, set it here
     */
    private fun statusPrint(i: Int, total: Int, printEveryN: Int = 1) {
        if(logger.isDebugEnabled) {
            if (i % printEveryN == 0) {
                //print("*")
                logger.debug("$i of $total")
            }
        }
    }

    /**
     * Quickbundles, a relatively simple clustering algorithm that subdivides the tracks based on their spatial
     * similarity. The reason we used it is to have a spatial structure while performing the edge bundling. With this
     * spatial structure, we can massively reduce the runtime of the edge bundling.
     */
    private fun quickBundles() {
        logger.debug("Starting quickbundles")

        // First prepare a (random) starting state
        val smallTracks: Array<Array<PointWithMeta>> = Array(trackSetBundled.size) {Array(clusteringTrackSize) { PointWithMeta(0.0f, 0.0f, 0.0f) }}
        val clustersReverse: Array<Int> = Array(trackSetBundled.size) {0}
        for(i in trackSetBundled.indices) {
            smallTracks[i] = resampleTrack(trackSetBundled[i], clusteringTrackSize)
        }
        val clusters: Array<ArrayList<Int>> = Array(numberOfClusters) {ArrayList<Int>()}
        for(i in 0 until numberOfClusters) {
            clusters[i] = ArrayList<Int>()
        }

        for(i in smallTracks.indices) {
            val randomCluster = (0 until numberOfClusters).random()
            clusters[randomCluster].add(i)
            clustersReverse[i] = randomCluster
        }
        var meanTracks = calculateMeanTracks(smallTracks, clustersReverse)

        // Now do the actual clustering
        for(i in 0 until clusteringIterations) {
            statusPrint(i, oclPoints.size * clusteringIterations)
            // Make current mapping empty
            for(j in 0 until numberOfClusters) {
                clusters[j] = ArrayList<Int>()
            }

            // Sort tracks into the clusters
            for(t in smallTracks.indices) {
                var lowestDistance: Float = MAX_VALUE
                var bestIndex = -1
                for(c in 0 until numberOfClusters) {
                    val dist = distanceBetweenTracks(smallTracks[t], meanTracks[c])
                    if(dist < lowestDistance) {
                        lowestDistance = dist
                        bestIndex = c
                    }
                }
                clusters[bestIndex].add(t)
                clustersReverse[t] = bestIndex
            }
            meanTracks = calculateMeanTracks(smallTracks, clustersReverse)
        }
        println() // For new line after status-***
        resultClusters = clusters
        resultClustersReverse = clustersReverse
    }
}
