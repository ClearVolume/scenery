package graphics.scenery

import graphics.scenery.primitives.TextBoard
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.spatial.DefaultSpatial
import graphics.scenery.attribute.spatial.HasCustomSpatial
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.extensions.xyz
import org.joml.*
import java.lang.Math
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

/**
 * Camera class that may be targeted or oriented
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @constructor Creates a new camera with default position and right-handed
 *  coordinate system.
 */
open class Camera : DefaultNode("Camera"), HasRenderable, HasMaterial, HasCustomSpatial<Camera.CameraSpatial> {

    /** Enum class for camera projection types */
    enum class ProjectionType {
        Undefined, Perspective, Orthographic
    }

    /** Is the camera targeted? */
    var targeted = false

    /** Target, used if [targeted] is true */
    var target: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    /** Forward vector of the camera, used if not targeted */
    var forward: Vector3f = Vector3f(0.0f, 0.0f, 1.0f)
    /** Up vector of the camera, used if not targeted */
    var up: Vector3f = Vector3f(0.0f, 1.0f, 0.0f)
    /** Right vector of the camera */
    var right: Vector3f = Vector3f(1.0f, 0.0f, 0.0f)
    /** FOV of the camera **/
    open var fov: Float = 70.0f
    /** Z buffer near plane */
    var nearPlaneDistance = 0.05f
    /** Z buffer far plane location */
    var farPlaneDistance = 1000.0f
    /** delta T from the renderer */
    var deltaT = 0.0f
    /** Projection the camera uses */
    var projectionType: ProjectionType = ProjectionType.Undefined
    /** Width of the projection */
    open var width: Int = 0
    /** Height of the projection */
    open var height: Int = 0
    /** View-space coordinate system e.g. for frustum culling. */
    var viewSpaceTripod: Tripod
        protected set
    /** Disables culling for this camera. */
    var disableCulling: Boolean = false

    init {
        this.nodeType = "Camera"
        this.viewSpaceTripod = cameraTripod()
        this.name = "Camera-${counter.incrementAndGet()}"
        addSpatial()
        addRenderable()
        addMaterial()
    }

    override fun createSpatial(): CameraSpatial {
        return CameraSpatial(this)
    }

    /**
     * Class to contain local coordinate systems with [x], [y], and [z] axis.
     */
    data class Tripod(val x: Vector3f, val y: Vector3f, val z: Vector3f)

    /**
     * Returns the current aspect ratio
     */
    fun aspectRatio(): Float {
        if(projectionType == ProjectionType.Undefined) {
            logger.warn("Querying aspect ratio but projection type is undefined")
            return 1.0f
        }

        if(width < 0.0001f || height < 0.0001f) {
            logger.warn("Width or height too small, returning 1.0f")
        }

        val scaleWidth = if(this is DetachedHeadCamera && this.tracker != null) {
            0.5f
        } else {
            1.0f
        }

        return (width*scaleWidth)/height
    }

    /**
     * Create a perspective projection camera
     */
    fun perspectiveCamera(fov: Float, width: Int, height: Int, nearPlaneLocation: Float = 0.1f, farPlaneLocation: Float = 1000.0f) {
        this.nearPlaneDistance = nearPlaneLocation
        this.farPlaneDistance = farPlaneLocation
        this.fov = fov

        this.width = width
        this.height = height

        spatial {
            this.projection = Matrix4f().perspective(
                this@Camera.fov / 180.0f * Math.PI.toFloat(),
                width.toFloat() / height.toFloat(),
                this@Camera.nearPlaneDistance,
                this@Camera.farPlaneDistance
            )
        }

        this.projectionType = ProjectionType.Perspective
    }

    /**
     * Create a orthographic projection camera.
     */
    fun orthographicCamera(fov: Float, width: Int, height: Int, nearPlaneLocation: Float = 0.1f, farPlaneLocation: Float = 1000.0f) {
        this.nearPlaneDistance = nearPlaneLocation
        this.farPlaneDistance = farPlaneLocation
        this.fov = fov

        this.width = width
        this.height = height

        spatial {
            this.projection = Matrix4f().orthoSymmetric(width.toFloat(), height.toFloat(), nearPlaneLocation, farPlaneLocation)
        }
        this.projectionType = ProjectionType.Orthographic
    }

    /**
     * Returns the list of objects (as [Scene.RaycastResult]) under the screen space position
     * indicated by [x] and [y], sorted by their distance to the observer.
     */
    @JvmOverloads fun getNodesForScreenSpacePosition(x: Int, y: Int,
                                                       ignoredObjects: List<Class<*>> = emptyList(),
                                                       debug: Boolean = false): Scene.RaycastResult {
        val (worldPos, worldDir) = screenPointToRay(x, y)

        val scene = getScene()
        if(scene == null) {
            logger.warn("No scene found for $this, returning empty list for raycast.")
            return Scene.RaycastResult(emptyList(), worldPos, worldDir)
        }

        return scene.raycast(worldPos, worldDir, ignoredObjects, debug)
    }

    /**
     * Returns the starting position and direction of a ray starting at the the screen space position
     * indicated by [x] and [y] targeting away from the camera.
     *
     * Returns (worldPos, worldDir)
     */
    fun screenPointToRay(x: Int, y: Int): Pair<Vector3f, Vector3f> {
        val view = (if (targeted) target - spatial().position else  forward).normalize()
        var h = Vector3f(view).cross(up).normalize()
        var v = Vector3f(h).cross(view)

        val fov = fov * Math.PI / 180.0f
        val lengthV = tan(fov / 2.0).toFloat() * nearPlaneDistance
        val lengthH = lengthV * (width / height)

        v *= lengthV
        h *= lengthH

        val posX = (x - width / 2.0f) / (width / 2.0f)
        val posY = -1.0f * (y - height / 2.0f) / (height / 2.0f)

        val worldPos = spatial().position + view * nearPlaneDistance + h * posX + v * posY
        val worldDir = (worldPos - spatial().position).normalize()
        return Pair(worldPos, worldDir)
    }

    /**
     * Returns true if this camera can see the given [node], which occurs when the node is
     * in the camera's frustum.
     *
     * This method is based on the Radar Method from Game Programming Gems 5, with the code
     * adapted from [lighthouse3d.com](http://www.lighthouse3d.com/tutorials/view-frustum-culling/radar-approach-testing-points/).
     *
     * In this method, a camera-local coordinate tripod is constructed, and candidate points are projected
     * onto this tripod one after the other. For Z, the length of the vector has to be between the [nearPlaneDistance]
     * and the [farPlaneDistance] for the point to be visible, for Y it has to fit into the height of the field of
     * view at that point, and for X into its width. For the spheres we test here from [Node]'s [getMaximumBoundingBox],
     * we add to the point the radius of the bounding sphere, and take stretching/skewing according to the [aspectRatio]
     * into account.
     *
     * If a sphere is intersecting any boundary of the view frustum, the node is assumed to be visible.
     */
    fun canSee(node: Node): Boolean {
        // TODO: Figure out how to efficiently cull instances
        if(disableCulling || node is InstancedNode || node is DisableFrustumCulling) {
            return true
        }

        val bs = node.getMaximumBoundingBox().getBoundingSphere()
        val sphereY = 1.0f/cos(PI/180.0f*fov)
        val tanFov = tan(PI*(fov/180.0f)*0.5f).toFloat()
        val angleX = atan(tanFov * aspectRatio())
        val sphereX = 1.0f/cos(angleX)
        val (x, y, z) = viewSpaceTripod

        val v = bs.origin - spatial().position
        var result = true

        // check whether the sphere is within the Z bounds
        val az = v.dot(z)
        if(az > farPlaneDistance + bs.radius || az < nearPlaneDistance - bs.radius) {
            return false
        }

        // check whether the sphere is within the height of the frustum
        val ay = v.dot(y)
        val d = sphereY * bs.radius
        val dzy = az * tanFov
        if(ay > dzy + d || ay < -dzy - d) {
            return false
        }

        // check whether the sphere is within the width of the frustum
        val ax = v.dot(x)
        val dzx = az * tanFov * aspectRatio()
        val dx = sphereX * bs.radius
        if(ax > dzx + dx || ax < -dzx - dx) {
            return false
        }

        // check all the three cases where the sphere might be just barely intersecting.
        if(az > farPlaneDistance - bs.radius || az < nearPlaneDistance + bs.radius) {
            result = true
        }

        if(ay > az - d || ay < -az + d) {
            result = true
        }

        if (ax > az - dx || ax < -az + dx) {
            result = true
        }

        return result
    }

    /**
     * Returns the view-space coordinate system of the camera as [Tripod].
     */
    fun cameraTripod(): Tripod {
        val z = Vector3f(forward)
        val x = Vector3f(z).cross(up).normalize()
        val y = Vector3f(x).cross(z).normalize()

        return Tripod(x, y, z)
    }

    /**
     * Shows a [message] to the user, at a distance of [distance] meters.
     * The message can by styled by [size] (in meters), [messageColor] and [backgroundColor].
     *
     * It will be shown for [duration] milliseconds, with a default of 3000.
     */
    @JvmOverloads fun showMessage(message: String, distance: Float = 0.75f, size: Float = 0.05f, messageColor: Vector4f = Vector4f(1.0f), backgroundColor: Vector4f = Vector4f(0.0f), duration: Int = 3000) {
        val tb = TextBoard()
        tb.fontColor = messageColor
        tb.backgroundColor = backgroundColor
        tb.text = message
        tb.spatial {
            scale = Vector3f(size, size, size)
            position = Vector3f(0.0f, 0.0f, -1.0f * distance)
        }

        @Suppress("UNCHECKED_CAST")
        val messages = metadata.getOrPut("messages", { mutableListOf<Node>() }) as? MutableList<Node>?
        messages?.forEach { this.removeChild(it) }
        messages?.clear()

        messages?.add(tb)
        this.addChild(tb)

        thread {
            Thread.sleep(duration.toLong())

            this.removeChild(tb)
            messages?.remove(tb)
        }
    }

    companion object {
        protected val counter = AtomicInteger(0)
    }


    open class CameraSpatial(val camera: Camera): DefaultSpatial(camera) {
        /** View matrix of the camera. Setting the view matrix will re-set the forward
         *  vector of the camera according to the given matrix.
         */
        override var view: Matrix4f = Matrix4f().identity()
            set(m) {
                m.let {
                    camera.right = Vector3f(m.get(0, 0), m.get(1, 0), m.get(2, 0)).normalize()
                    camera.up = Vector3f(m.get(0, 1), m.get(1, 1), m.get(2, 1)).normalize()
                    camera.forward = Vector3f(m.get(0, 2), m.get(1, 2), m.get(2, 2)).normalize() * -1.0f

                    camera.viewSpaceTripod = camera.cameraTripod()

                    this.needsUpdate = true
                    this.needsUpdateWorld = true

                    if(!camera.targeted) {
                        camera.target = this.position + camera.forward
                    }
                }
                field = m
            }

        /** Rotation of the camera. The rotation is applied after the view matrix */
        override var rotation: Quaternionf = Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)
            set(q) {
                q.let {
                    field = q
                    val m = Matrix4f().set(q)
                    camera.forward = Vector3f(m.get(0, 2), m.get(1, 2), m.get(2, 2)).normalize() * -1.0f
                    camera.viewSpaceTripod = camera.cameraTripod()

                    this.needsUpdate = true
                    this.needsUpdateWorld = true
                }
            }

        override fun composeModel() {
            model = getTransformation().invert()
        }

        /**
         * Returns this camera's transformation matrix.
         */
        open fun getTransformation(): Matrix4f {
            val tr = Matrix4f().translate(this.position * (-1.0f))
            val r = Matrix4f().set(this.rotation)

            return r * tr
        }

        /**
         * Returns this camera's transformation matrix, including a
         * [preRotation] that is applied before the camera's transformation.
         */
        open fun getTransformation(preRotation: Quaternionf): Matrix4f {
            val tr = Matrix4f().translate(this.position * (-1.0f))
            val r = Matrix4f().set(preRotation * this.rotation)

            return r * tr
        }

        /**
         * Returns this camera's transformation for eye with index [eye].
         */
        open fun getTransformationForEye(eye: Int): Matrix4f {
            val tr = Matrix4f().translate(this.position * (-1.0f))
            val r = Matrix4f().set(this.rotation)

            return r * tr
        }

        /**
         * Transforms a 3D vector from view space to world coordinates.
         *
         * @param v - The vector to be transformed into world space.
         * @return Vector3f - [v] transformed into world space.
         */
        fun viewToWorld(v: Vector3f): Vector4f =
            Matrix4f(this.view).invert().transform(Vector4f(v.x(), v.y(), v.z(), 1.0f))

        /**
         * Transforms a 4D vector from view space to world coordinates.
         *
         * @param v - The vector to be transformed into world space.
         * @return Vector3f - [v] transformed into world space.
         */
        fun viewToWorld(v: Vector4f): Vector4f =
            Matrix4f(view).invert().transform(v)

        /**
         * Transforms a 2D [vector] in screen space to view space and returns this 3D vector.
         */
        fun viewportToView(vector: Vector2f): Vector3f {
            return Matrix4f(projection).invert().transform(Vector4f(vector.x, vector.y, 0.0f, 1.0f)).xyz()
        }

        /**
         * Transforms a 2D/3D [vector] from NDC coordinates to world coordinates.
         * If the vector is 2D, [nearPlaneDistance] is assumed for the Z value, otherwise
         * the Z value from the vector is taken.
         */
        fun viewportToWorld(vector: Vector2f): Vector3f {
            val pv = Matrix4f(projection)
            pv.mul(getTransformation())
            val ipv = Matrix4f(pv).invert()

            var worldSpace = ipv.transform(Vector4f(vector.x(), vector.y(), 0.0f, 1.0f))

            worldSpace = worldSpace.times(1.0f/worldSpace.w())
//        worldSpace.set(2, offset)
            return worldSpace.xyz()
        }

    }

    @Deprecated(message = "", replaceWith = ReplaceWith("spatial().viewportToWorld(vector)"))
    fun viewportToWorld(vector: Vector2f): Vector3f {
        return spatial().viewportToWorld(vector)
    }

    @Deprecated(message = "", replaceWith = ReplaceWith("spatial().viewportToView(vector)"))
    fun viewportToView(vector: Vector2f): Vector3f {
        return spatial().viewportToView(vector)
    }

    @Deprecated(message = "", replaceWith = ReplaceWith("spatial().viewToWorld(vector)"))
    fun viewToWorld(vector: Vector4f): Vector4f {
        return spatial().viewToWorld(vector)
    }

    @Deprecated(message = "", replaceWith = ReplaceWith("spatial().viewToWorld(vector)"))
    fun viewToWorld(vector: Vector3f): Vector4f {
        return spatial().viewToWorld(vector)
    }

    @Deprecated(message = "", replaceWith = ReplaceWith("spatial().getTransformationForEye(eye)"))
    fun getTransformationForEye(eye: Int): Matrix4f {
        return spatial().getTransformationForEye(eye)
    }

    @Deprecated(message = "", replaceWith = ReplaceWith("spatial().getTransformation(preRotation)"))
    open fun getTransformation(preRotation: Quaternionf): Matrix4f {
        return spatial().getTransformation(preRotation)
    }
}


