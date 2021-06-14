package graphics.scenery.controls.eyetracking

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.readValue
import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.backends.Display
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import kotlinx.coroutines.*
import org.joml.Vector3f
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMsg
import org.zeromq.ZPoller
import java.io.Serializable
import java.util.*

/**
 * Support class for Pupil Labs eye trackers -- pupil-labs.com
 *
 * [calibrationType] can be set to screen space or world space, and [host] is the host where
 * the Pupil service is running on, and [port] its port.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class PupilEyeTracker(val calibrationType: CalibrationType, val host: String = "localhost", val port: Int = System.getProperty("scenery.PupilEyeTracker.Port", "50020").toIntOrNull() ?: 50020) {
    /** Shall we do a screen-space or world-space calibration? */
    enum class CalibrationType { ScreenSpace, WorldSpace}

    private val logger by LazyLogger()

    private val zmqContext = ZContext(4)
    private val req = zmqContext.createSocket(ZMQ.REQ)
    private val objectMapper = ObjectMapper(MessagePackFactory())

    private val subscriptionPort: Int

    var isCalibrated = false
        private set
    private var calibrating = false

    var onGazeReceived: ((Gaze) -> Unit)? = null
    var onCalibrationInProgress: (() -> Unit)? = null
    var onCalibrationFailed: (() -> Unit)? = null
    var onCalibrationSuccess: (() -> Unit)? = null
    var gazeConfidenceThreshold = 0.65f

    /**
     * Stores gaze data, and retrieves various properties
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Gaze(val confidence: Float = 0.0f,
                    val timestamp: Float = 0.0f,
                    val eyeId: Int? = null,

                    val norm_pos: FloatArray = floatArrayOf(),
                    val gaze_point_3d: FloatArray? = floatArrayOf(),
                    val eye_centers_3d: HashMap<Int, FloatArray>? = hashMapOf(),
                    val gaze_normals_3d: HashMap<Int, FloatArray>? = hashMapOf()) {


        /** Returns the normalized gaze position. */
        fun normalizedPosition() = this.norm_pos.toVector3f()
        /** Returns the point the user is gazing at. */
        fun gazePoint() = gaze_point_3d.toVector3f()

        /** Returns the center of the left eye. */
        @Suppress("unused")
        fun leftEyeCenter() = eye_centers_3d?.getOrDefault(0, floatArrayOf(0.0f, 0.0f, 0.0f)).toVector3f()
        /** Returns the center of the right eye. */
        @Suppress("unused")
        fun rightEyeCenter() = eye_centers_3d?.getOrDefault(1, floatArrayOf(0.0f, 0.0f, 0.0f)).toVector3f()

        /** Returns the normal orientation of the left eye. */
        @Suppress("unused")
        fun leftGazeNormal() = gaze_normals_3d?.getOrDefault(0, floatArrayOf(0.0f, 0.0f, 0.0f)).toVector3f()
        /** Returns the normal orientation of the right eye. */
        @Suppress("unused")
        fun rightGazeNormal() = gaze_normals_3d?.getOrDefault(1, floatArrayOf(0.0f, 0.0f, 0.0f)).toVector3f()

        private fun FloatArray?.toVector3f(): Vector3f {
            return if(this != null) {
                Vector3f(this[0], this[1], this.getOrElse(2, { 0.0f }))
            } else {
                Vector3f(0.0f, 0.0f, 0.0f)
            }
        }

        /**
         * Compares two gaze data points with each other, returning true
         * only if they match in all aspects.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as? Gaze ?: return false

            if (confidence != other.confidence) return false
            if (timestamp != other.timestamp) return false
            if (!Arrays.equals(norm_pos, other.norm_pos)) return false
            if (!Arrays.equals(gaze_point_3d, other.gaze_point_3d)) return false
            if (eye_centers_3d != other.eye_centers_3d) return false
            if (gaze_normals_3d != other.gaze_normals_3d) return false

            return true
        }

        /**
         * Returns the hash code of this gaze data point.
         */
        override fun hashCode(): Int {
            var result = confidence.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + norm_pos.contentHashCode()
            result = 31 * result + Arrays.hashCode(gaze_point_3d)
            result = 31 * result + (eye_centers_3d?.hashCode() ?: 0)
            result = 31 * result + (gaze_normals_3d?.hashCode() ?: 0)
            return result
        }
    }


    private var currentPupilDatumLeft = HashMap<Any, Any>()
    private var currentPupilDatumRight = HashMap<Any, Any>()

    /** Stores the current gaze data point. */
    var currentGazeLeft: Gaze? = null
        private set
    var currentGazeRight: Gaze? = null
        private set

    private var frameLeft: ByteArray? = null
    private var frameRight: ByteArray? = null
    private var onReceiveFrame: ((Int, ByteArray) -> Any)? = null

    init {
        logger.info("Connecting to Pupil at $host:$port (if this takes a long time, it's a good idea to start Pupil first and make sure it's listening on the right port ;)) ...")
        req.connect("tcp://$host:$port")
        req.send("SUB_PORT")

        subscriptionPort = req.recvStr().toInt()
        logger.info("Subscription port received as $subscriptionPort")

        val module = SimpleModule()
        module.addKeyDeserializer(Int::class.java, object : KeyDeserializer() {
            override fun deserializeKey(key: String?, ctxt: DeserializationContext?): Any {
                return Integer.valueOf(key)
            }
        })
        objectMapper.registerModule(module)
    }

    protected fun getPupilTimestamp(): Float {
        req.send("t")
        return req.recvStr().toFloat()
    }

    protected fun notify(dict: HashMap<String, Any>): String {
        req.sendMore("notify.${dict["subject"]}")
        req.send(objectMapper.writeValueAsBytes(dict))

        val answer = req.recvStr()
        logger.debug(answer)
        return answer
    }

    private val subscriberSockets = HashMap<String, Job>()
    private fun subscribe(topic: String) {
        if(!subscriberSockets.containsKey(topic)) {

            val job = GlobalScope.launch {
                val socket = zmqContext.createSocket(ZMQ.SUB)
                val poller = ZPoller(zmqContext)
                poller.register(socket, ZMQ.Poller.POLLIN)

                try {
                    socket.connect("tcp://$host:$subscriptionPort")
                    socket.subscribe(topic)
                    logger.debug("Subscribed to topic $topic")

                    while (isActive) {
                        poller.poll(10)

                        if(poller.isReadable(socket)) {
                            val msg = ZMsg.recvMsg(socket)
                            val msgType = msg.popString()
//                            logger.info("Received message type $msgType")

                            when(msgType) {
                                "notify.calibration.successful" -> {
                                    logger.info("Calibration successful.")
                                    calibrating = false
                                    isCalibrated = true
                                }

                                "notify.calibration.failed" -> {
                                    logger.error("Calibration failed.")
                                    onCalibrationFailed?.invoke()

                                    calibrating = false
                                }

                                "pupil.0", "pupil.1" -> {
                                    val bytes = msg.pop().data
                                    val dict = objectMapper.readValue<HashMap<Any, Any>>(bytes)
                                    val confidence = (dict["confidence"] as Double).toFloat()
                                    val eyeId = dict["id"] as Int

                                    if(confidence > 0.8) {
                                        when(eyeId) {
                                            0 -> currentPupilDatumLeft = dict
                                            1 -> currentPupilDatumRight = dict
                                        }
                                    }
                                }

                                "gaze.2d.0.",
                                "gaze.2d.1." -> {
                                    TODO("2D gaze mapping needs a revamp")
                                }

                                "gaze.3d.0.",
                                "gaze.3d.1.",
                                "gaze.3d.01." -> {
                                    val bytes = msg.pop().data
                                    val g = objectMapper.readValue(bytes, Gaze::class.java)
//                                    logger.info("Received data: ${String(bytes)}")

                                    if(g.confidence > gazeConfidenceThreshold) {

                                        if(msgType.contains(".01.")) {
//                                            gazeMode = 1
//                                            logger.info("Received binocular gaze")

                                            val p = g.gaze_point_3d ?: floatArrayOf(0.0f, 0.0f, 0.0f)
                                            var vp = Vector3f(p[0], p[1], p[2])

//                                            if(vp.times(Vector3f(0.0f, 0.0f, -1.0f)) >= PI/2.0f) {
//                                                logger.info("Inverting gaze direction")
//                                                vp *= (-1.0f)
//                                            }

                                            vp = vp * (1.0f/pupilToSceneryRatio)

                                            val ng = Gaze(
                                                g.confidence,
                                                g.timestamp,
                                                2,
                                                g.norm_pos,
                                                floatArrayOf(vp.x, vp.y, vp.z),
                                                g.eye_centers_3d,
                                                g.gaze_normals_3d
                                            )

                                            onGazeReceived?.invoke(ng)
                                        } else {
                                            if (msgType.contains(".0.")) {
                                                currentGazeLeft = g
                                            }

                                            if (msgType.contains(".1.")) {
                                                currentGazeRight = g
                                            }

                                            val p = g.gaze_point_3d ?: floatArrayOf(0.0f, 0.0f, 0.0f)
                                            var vp = Vector3f(p[0], p[1], p[2])

                                            vp *= (1.0f/pupilToSceneryRatio)

                                            val ng = Gaze(
                                                g.confidence,
                                                g.timestamp,
                                                msgType.substringAfterLast("d.").replace(".", "").toInt(),
                                                g.norm_pos,
                                                floatArrayOf(vp.x, vp.y, vp.z),
                                                g.eye_centers_3d,
                                                g.gaze_normals_3d
                                            )

                                            onGazeReceived?.invoke(ng)
                                        }
                                    }
                                }

                                "frame.eye.0" -> {
                                    msg.pop()
                                    val bytes = msg.pop().data
                                    frameLeft = bytes

                                    onReceiveFrame?.invoke(0, bytes)
                                }

                                "frame.eye.1" -> {
                                    msg.pop()
                                    val bytes = msg.pop().data
                                    frameRight = bytes

                                    onReceiveFrame?.invoke(1, bytes)
                                }
                            }

                            msg.destroy()
                        }
                    }
                } finally {
                    logger.debug("Closing topic socket for $topic")
                    poller.unregister(socket)
                    poller.close()
                    socket.close()
                }
            }

            subscriberSockets.put(topic, job)
        }
    }

    private fun unsubscribe(topic: String) = runBlocking {
        if(subscriberSockets.containsKey(topic)) {
            logger.debug("Cancelling subscription of topic \"$topic\"")
            subscriberSockets.get(topic)?.cancelAndJoin()
            subscriberSockets.get(topic)?.join()

            subscriberSockets.remove(topic)
        }
    }

    /**
     * Subscribes to the frames seen by the eye tracking cameras, which
     * [onReceiveAction] containing a lambda that takes the eye ID and a byte
     * array containing the compressed RGB texture data from one frame. [onReceiveAction]
     * will be called for each frame receive by all eye cameras, until
     * [unsubscribeFrames] is called.
     *
     * TODO: Automatically decompress the frame data received from Pupil.
     */
    fun subscribeFrames(onReceiveAction: (Int, ByteArray) -> Unit) {
        notify(hashMapOf(
            "subject" to "start_plugin",
            "name" to "Frame_Publisher",
            "format" to "gray"
        ))
        subscribe("frame.eye.0")
        subscribe("frame.eye.1")

        onReceiveFrame = onReceiveAction
    }

    /**
     * Unsubscribes from receiving frames from the eye tracking cameras.
     */
    fun unsubscribeFrames() {
        notify(hashMapOf(
            "subject" to "stop_plugin",
            "name" to "Frame_Publisher"
        ))
        unsubscribe("frame.eye.0")
        unsubscribe("frame.eye.1")
    }

    /**
     * Runs a gaze calibration, using [cam] as origin to display the calibration points.
     * Requires a [hmd], and a [calibrationTarget] can be given to be moved around for gaze calibration.
     */
    fun calibrate(cam: Camera, hmd: Display, generateReferenceData: Boolean = false, calibrationTarget: Node? = null): Boolean {
        // Threshold for samples to ignore in the beginning
        val eyeMovingSamples = 15
        subscribe("notify.calibration.successful")
        subscribe("notify.calibration.failed")
        subscribe("pupil.")

        notify(hashMapOf(
            "subject" to "eye_process.should_start.0",
            "eye_id" to 0,
            "args" to emptyMap<String, Any>()
        ))

        notify(hashMapOf(
            "subject" to "eye_process.should_start.1",
            "eye_id" to 1,
            "args" to emptyMap<String, Any>()
        ))

        Thread.sleep(2000)


        when(calibrationType) {
            CalibrationType.ScreenSpace -> {
                notify(hashMapOf(
                    "subject" to "start_plugin",
                    "name" to "HMD_Calibration",
                    "args" to emptyMap<String, Any>()
                ))

                notify(hashMapOf(
                    "subject" to "calibration.should_start",
                    "hmd_video_frame_size" to listOf(hmd.getRenderTargetSize().x().toInt(), hmd.getRenderTargetSize().y().toInt()),
                    "outlier_threshold" to 35
                ))
            }

            CalibrationType.WorldSpace -> {
                notify(hashMapOf(
                    "subject" to "start_plugin",
                    "name" to "HMD_Calibration_3D",
                    "args" to emptyMap<String, Any>()
                ))

                val shiftLeftEye = Vector3f(
                    hmd.getHeadToEyeTransform(0)[3, 0],
                    hmd.getHeadToEyeTransform(0)[3, 1],
                    hmd.getHeadToEyeTransform(0)[3, 2]) * 1000.0f

                val shiftRightEye = Vector3f(
                    hmd.getHeadToEyeTransform(1)[3, 0],
                    hmd.getHeadToEyeTransform(1)[3, 1],
                    hmd.getHeadToEyeTransform(1)[3, 2]) * 1000.0f

                logger.info("Eye shifts are L:$shiftLeftEye/R:$shiftRightEye")

                notify(hashMapOf(
                    "subject" to "calibration.should_start",
                    "hmd_video_frame_size" to listOf(hmd.getRenderTargetSize().x().toInt(), hmd.getRenderTargetSize().y().toInt()),
                    "outlier_threshold" to 35,
                    "translation_eye0" to floatArrayOf(shiftLeftEye.x, shiftLeftEye.y, shiftLeftEye.z),
                    "translation_eye1" to floatArrayOf(shiftRightEye.x, shiftRightEye.y, shiftRightEye.z)
                ))
            }
        }

        calibrating = true
        calibrationTarget?.visible = true
        val referenceData = arrayListOf<HashMap<String, Serializable>>()

        if(generateReferenceData) {
            val numReferencePoints = if(calibrationType == CalibrationType.ScreenSpace) {
                6
            } else {
                18
            }

            val samplesPerPoint = 120

            val (posKeyName, posGenerator: CalibrationPointGenerator) = when(calibrationType) {
                CalibrationType.ScreenSpace -> "norm_pos" to CircleScreenSpaceCalibrationPointGenerator()
                CalibrationType.WorldSpace -> "mm_pos" to LayeredCircleWorldSpaceCalibrationPointGenerator()
            }

            val positionList = (0 .. numReferencePoints).map {
                posGenerator.generatePoint(cam, it, numReferencePoints)
            }

            positionList.map { normalizedScreenPos ->
                logger.info("Subject looking at ${normalizedScreenPos.local}/${normalizedScreenPos.world}")
                val position = Vector3f(normalizedScreenPos.world)

                val calibrationPosition = if(calibrationType == CalibrationType.ScreenSpace) {
                    calibrationTarget?.spatialOrNull()?.position = position + cam.forward * 0.15f
                    val l = normalizedScreenPos.local
                    floatArrayOf(l.x, l.y, l.z)
                } else {
                    calibrationTarget?.spatialOrNull()?.position = position
                    val p = Vector3f(normalizedScreenPos.local) * pupilToSceneryRatio
                    p.x = p.get(0) * 1.0f
                    p.y = p.get(1) * -1.0f * cam.aspectRatio()
                    p.z = p.get(2) * 1.0f
                    floatArrayOf(p.x, p.y, p.z)
                }

                calibrationTarget?.ifMaterial {
                    if(normalizedScreenPos.local.x() == 0.5f && normalizedScreenPos.local.y() == 0.5f) {
                        diffuse = Vector3f(1.0f, 1.0f, 0.0f)
                    } else {
                        diffuse = Vector3f(1.0f, 1.0f, 1.0f)
                    }
                }

                (0 until samplesPerPoint).forEach {
                    val timestamp = getPupilTimestamp()

                    val datum0 = hashMapOf<String, Serializable>(
                        posKeyName to calibrationPosition,
                        "timestamp" to timestamp,
                        "id" to 0
                    )

                    val datum1 = hashMapOf<String, Serializable>(
                        posKeyName to calibrationPosition,
                        "timestamp" to timestamp,
                        "id" to 1
                    )

                    if(it > eyeMovingSamples) {
                        referenceData.add(datum0)
                        referenceData.add(datum1)
                    }

                    Thread.sleep(20)
                }
            }

            logger.info("Generated ${referenceData.size} calibration points ($samplesPerPoint samples x $numReferencePoints points)")
        }

        notify(hashMapOf<String, Any>(
            "subject" to "calibration.add_ref_data",
            "ref_data" to referenceData.toArray()
        ))

        notify(hashMapOf(
            "subject" to "calibration.should_stop"
        ))

        calibrationTarget?.visible = false
        logger.info("Done collecting calibration data, running fitting now.")
        onCalibrationInProgress?.invoke()

        while(calibrating) {
            Thread.sleep(100)
        }

        unsubscribe("notify.calibration.successful")
        unsubscribe("notify.calibration.failed")

        if(isCalibrated) {
            logger.info("Calibration succeeded, subscribing to gaze data")
            subscribe("gaze")

            onCalibrationSuccess?.invoke()

            return true
        }

        return false
    }

    companion object {
        /** Pupil uses a millimeter-based coordinate system, while scenery uses meters, this ratio translates between them. */
        const val pupilToSceneryRatio = 1000.0f
    }
}
