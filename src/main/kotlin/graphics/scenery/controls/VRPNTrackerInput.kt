package graphics.scenery.controls

import org.joml.Matrix4f
import org.joml.Vector3f
import graphics.scenery.Camera
import graphics.scenery.mesh.Mesh
import graphics.scenery.Node
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.times
import org.joml.Quaternionf
import vrpn.Loader
import vrpn.TrackerRemote
import vrpn.TrackerRemoteListener
import java.util.*

/**
* TrackerInput for handling VRPN-based devices
*
* @author Ulrik Günther <hello@ulrik.is>
*/

class VRPNTrackerInput(trackerAddress: String = "device@locahost:5500") : TrackerInput {
    private val logger by LazyLogger()

    var tracker: TrackerRemote? = null

    var deviceName = trackerAddress.substringBefore("@")
    var trackerLocation = trackerAddress.substringAfter("@")

    var trackerAddress: String = trackerAddress
        set(value) {
            field = value
            logger.info("Initializing VRPN device $deviceName at $trackerLocation")
            tracker = initializeTracker(value)
        }

    var listener: TrackerRemoteListener? = null

    var cachedOrientation: Quaternionf = Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)

    var cachedPosition: Vector3f = Vector3f(0.0f, 1.5f, 1.5f)

    var stats = Timer()
    var vrpnMsgCount = 0L

    var positionScaling: Float = 1.0f

    override var events = TrackerInputEventHandlers()

    init {
        Loader.loadNatives()

        this.trackerAddress = trackerAddress

        stats.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                logger.debug("VRPN msg/sec: $vrpnMsgCount")
                vrpnMsgCount = 0
            }
        }, 0, 1000)

        logger.info("${tracker?.isConnected}/${tracker?.isLive}")
    }

    private fun initializeTracker(address: String): TrackerRemote {
        val t = TrackerRemote(address, null, null, null, null)
//        t.setUpdateRate(500.0)
//        t.addAccelerationChangeListener(this)
//        t.addPositionChangeListener(this)
//        t.addVelocityChangeListener(this)

        listener = TrackerRemoteListener(t)
        listener?.setModeLastTrackerUpdate()
        listener?.setModeLastAccelerationUpdate()
        listener?.setModeLastVelocityUpdate()

        return t
    }

    /**
     * Returns the orientation of the HMD
     *
     * @returns Matrix4f with orientation
     */
    override fun getOrientation(): Quaternionf {
        return cachedOrientation
    }

    /**
     * Returns the orientation of the given device, or a unit quaternion if the device is not found.
     *
     * @returns Matrix4f with orientation
     */
    override fun getOrientation(id: String): Quaternionf {
        return cachedOrientation
    }

    /**
     * Returns the absolute position as Vector3f
     *
     * @return HMD position as Vector3f
     */
    override fun getPosition(): Vector3f {
        return cachedPosition
    }

    /**
     * Returns the HMD pose
     *
     * @return HMD pose as Matrix4f
     */
    override fun getPose(): Matrix4f {
        return Matrix4f().set(cachedOrientation)
    }

    /**
     * Returns a list of poses for the devices [type] given.
     *
     * @return Pose as Matrix4f
     */
    override fun getPose(type: TrackedDeviceType): List<TrackedDevice> {
        return listOf(TrackedDevice(TrackedDeviceType.HMD, "VRPN", getPose(), System.nanoTime()))
    }

    /**
     * Returns the HMD pose per eye
     *
     * @return HMD pose as Matrix4f
     */
    override fun getPoseForEye(eye: Int): Matrix4f {
        return this.getPose()
    }

    /**
     * Check whether the HMD is initialized and working
     *
     * @return True if HMD is initialiased correctly and working properly
     */
    override fun initializedAndWorking(): Boolean {
        return (tracker?.isLive ?: false) && (tracker?.isConnected ?: false)
    }

    /**
     * update state
     */
    override fun update() {
        listener?.let {

            it.lastTrackerUpdate?.let {
                cachedPosition = Vector3f(it.pos[0].toFloat(), it.pos[1].toFloat(), -it.pos[2].toFloat()) * positionScaling
//                val newOrientation = Quaternion(
//                   it.quat[0].toFloat(),
//                   -it.quat[2].toFloat(),
//                   -it.quat[1].toFloat(),
//                   it.quat[3].toFloat()
//                )
//                    .rotateByAngleX(-Math.PI.toFloat() / 2.0f)
//                    .normalize()
//
//                cachedOrientation = newOrientation.conjugate().normalize()

                vrpnMsgCount++
            }
        }
    }

    override fun getWorkingTracker(): TrackerInput? {
        if(tracker?.isLive ?: false && tracker?.isConnected ?: false) {
            return this
        } else {
            return null
        }
    }

    override fun loadModelForMesh(type: TrackedDeviceType, mesh: Mesh): Mesh {
        TODO("not implemented")
    }

    override fun loadModelForMesh(device: TrackedDevice, mesh: Mesh): Mesh {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun attachToNode(device: TrackedDevice, node: Node, camera: Camera?) {
        TODO("not implemented")
    }

    override fun getTrackedDevices(ofType: TrackedDeviceType): Map<String, TrackedDevice> {
        TODO("Not implemented yet")
    }
}
