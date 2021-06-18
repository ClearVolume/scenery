package graphics.scenery
import org.joml.Vector3f

class SplineCoaster(spline: Spline, val cam: () -> Camera?, override val listOfCameraFrames: List<FrenetFrame> =
    FrenetFramesCalc(spline).computeFrenetFrames(), override val offsetList: List<List<Vector3f>> = ArrayList<List<Vector3f>>()): Rollercoaster(cam)