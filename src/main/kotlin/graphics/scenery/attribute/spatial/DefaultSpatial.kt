package graphics.scenery.attribute.spatial

import graphics.scenery.Node
import graphics.scenery.Scene
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.extensions.*
import net.imglib2.Localizable
import net.imglib2.RealLocalizable
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import java.lang.Float.max
import java.lang.Float.min
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

open class DefaultSpatial(private var node: Node): Spatial {
    override var world: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var model: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var view: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var projection: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var scale: Vector3f by Delegates.observable(Vector3f(1.0f, 1.0f, 1.0f)) { property, old, new -> propertyChanged(property, old, new) }
    override var rotation: Quaternionf by Delegates.observable(Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)) { property, old, new -> propertyChanged(property, old, new) }
    override var position: Vector3f by Delegates.observable(Vector3f(0.0f, 0.0f, 0.0f)) { property, old, new -> propertyChanged(property, old, new) }
    override var wantsComposeModel = true
    override var needsUpdate = true
    override var needsUpdateWorld = true

    val logger by LazyLogger()

    @Suppress("UNUSED_PARAMETER")
    override fun <R> propertyChanged(property: KProperty<*>, old: R, new: R, custom: String) {
        if(property.name == "rotation"
            || property.name == "position"
            || property.name  == "scale"
            || property.name == custom) {
            needsUpdate = true
            needsUpdateWorld = true
        }
    }

    @Synchronized override fun updateWorld(recursive: Boolean, force: Boolean) {
        node.update.forEach { it.invoke() }

        if ((needsUpdate or force)) {
            if(wantsComposeModel) {
                this.composeModel()
            }

            needsUpdate = false
            needsUpdateWorld = true
        }

        if (needsUpdateWorld or force) {
            val p = node.parent
            if (p == null || p is Scene) {
                world.set(model)
            } else {
                world.set(p.spatialOrNull()?.world)
                world.mul(this.model)
            }
        }

        if (recursive) {
            node.children.forEach { it.spatialOrNull()?.updateWorld(true, needsUpdateWorld) }
            // also update linked nodes -- they might need updated
            // model/view/proj matrices as well
            node.linkedNodes.forEach { it.spatialOrNull()?.updateWorld(true, needsUpdateWorld) }
        }

        if(needsUpdateWorld) {
            needsUpdateWorld = false
        }

        node.postUpdate.forEach { it.invoke() }
    }

    override fun worldScale(): Vector3f {
        val wm = world
        val sx = Vector3f(wm[0,0],wm[0,1],wm[0,2]).length()
        val sy = Vector3f(wm[1,0],wm[1,1],wm[1,2]).length()
        val sz = Vector3f(wm[2,0],wm[2,1],wm[2,2]).length()

        return Vector3f(sx,sy,sz)
    }

    /**
     * This method composes the [model] matrices of the node from its
     * [position], [scale] and [rotation].
     */
    override fun composeModel() {

        @Suppress("SENSELESS_COMPARISON")
        if(position != null && rotation != null && scale != null) {
            model.translationRotateScale(
                Vector3f(position.x(), position.y(), position.z()),
                this.rotation,
                Vector3f(this.scale.x(), this.scale.y(), this.scale.z()))
        }
    }

    override fun centerOn(position: Vector3f): Vector3f {
        val min = node.getMaximumBoundingBox().min
        val max = node.getMaximumBoundingBox().max

        val center = (max - min) * 0.5f
        this.position = position - (node.getMaximumBoundingBox().min + center)

        return center
    }

    override fun putAbove(position: Vector3f): Vector3f {
        val center = centerOn(position)

        val diffY = center.y() + position.y()
        val diff = Vector3f(0.0f, diffY, 0.0f)
        this.position = this.position + diff

        return diff
    }

    override fun fitInto(sideLength: Float, scaleUp: Boolean): Vector3f {
        val min = node.getMaximumBoundingBox().min.xyzw()
        val max = node.getMaximumBoundingBox().max.xyzw()

        val maxDimension = (max - min).max()
        val scaling = sideLength/maxDimension

        if((scaleUp && scaling > 1.0f) || scaling <= 1.0f) {
            this.scale = Vector3f(scaling, scaling, scaling)
        } else {
            this.scale = Vector3f(1.0f, 1.0f, 1.0f)
        }

        return this.scale
    }

    override fun orientBetweenPoints(p1: Vector3f, p2: Vector3f, rescale: Boolean, reposition: Boolean): Quaternionf {
        val direction = p2 - p1
        val length = direction.length()

        this.rotation = Quaternionf().rotationTo(Vector3f(0.0f, 1.0f, 0.0f), direction.normalize())
        if(rescale) {
            this.scale = Vector3f(1.0f, length, 1.0f)
        }

        if(reposition) {
            this.position = Vector3f(p1)
        }

        return this.rotation
    }


    override fun intersects(other: Node): Boolean {
        node.boundingBox?.let { ownOBB ->
            other.boundingBox?.let { otherOBB ->
                return ownOBB.intersects(otherOBB)
            }
        }

        return false
    }

    override fun worldPosition(v: Vector3f?): Vector3f {
        val target = v ?: position
        return if(node.parent is Scene && v == null) {
            Vector3f(target)
        } else {
            world.transform(Vector4f().set(target, 1.0f)).xyz()
        }
    }

    /**
     * Performs a intersection test with an axis-aligned bounding box of this [Node], where
     * the test ray originates at [origin] and points into [dir].
     *
     * Returns a Pair of Boolean and Float, indicating whether an intersection is possible,
     * and at which distance.
     *
     * Code adapted from [zachamarz](http://gamedev.stackexchange.com/a/18459).
     */
    override fun intersectAABB(origin: Vector3f, dir: Vector3f): MaybeIntersects {
        val bbmin = node.getMaximumBoundingBox().min.xyzw()
        val bbmax = node.getMaximumBoundingBox().max.xyzw()

        val min = world.transform(bbmin)
        val max = world.transform(bbmax)

        // skip if inside the bounding box
        if(origin.isInside(min.xyz(), max.xyz())) {
            return MaybeIntersects.NoIntersection()
        }

        val invDir = Vector3f(1 / (dir.x() + Float.MIN_VALUE), 1 / (dir.y() + Float.MIN_VALUE), 1 / (dir.z() + Float.MIN_VALUE))

        val t1 = (min.x() - origin.x()) * invDir.x()
        val t2 = (max.x() - origin.x()) * invDir.x()
        val t3 = (min.y() - origin.y()) * invDir.y()
        val t4 = (max.y() - origin.y()) * invDir.y()
        val t5 = (min.z() - origin.z()) * invDir.z()
        val t6 = (max.z() - origin.z()) * invDir.z()

        val tmin = max(max(min(t1, t2), min(t3, t4)), min(t5, t6))
        val tmax = min(min(max(t1, t2), max(t3, t4)), max(t5, t6))

        // we are in front of the AABB
        if (tmax < 0) {
            return MaybeIntersects.NoIntersection()
        }

        // we have missed the AABB
        if (tmin > tmax) {
            return MaybeIntersects.NoIntersection()
        }

        // we have a match! calculate entry and exit points
        val entry = origin + dir * tmin
        val exit = origin + dir * tmax
        val localEntry = Matrix4f(world).invert().transform(Vector4f().set(entry, 1.0f))
        val localExit = Matrix4f(world).invert().transform(Vector4f().set(exit, 1.0f))

        return MaybeIntersects.Intersection(tmin, entry, exit, localEntry.xyz(), localExit.xyz())
    }

    private fun Vector3f.isInside(min: Vector3f, max: Vector3f): Boolean {
        return this.x() > min.x() && this.x() < max.x()
            && this.y() > min.y() && this.y() < max.y()
            && this.z() > min.z() && this.z() < max.z()
    }

    override fun localize(position: FloatArray?) {
        position?.set(0, this.position.x())
        position?.set(1, this.position.y())
        position?.set(2, this.position.z())
    }

    override fun localize(position: DoubleArray?) {
        position?.set(0, this.position.x().toDouble())
        position?.set(1, this.position.y().toDouble())
        position?.set(2, this.position.z().toDouble())
    }

    override fun getFloatPosition(d: Int): Float {
        return this.position[d]
    }

    override fun bck(d: Int) {
        move(-1, d)
    }

    override fun move(distance: Float, d: Int) {
        setPosition( getFloatPosition(d) + distance, d )
    }

    override fun move(distance: Double, d: Int) {
        setPosition( getDoublePosition(d) + distance, d )
    }

    override fun move(distance: RealLocalizable?) {
        distance?.getDoublePosition(0)?.let { move(it, 0) }
        distance?.getDoublePosition(1)?.let { move(it, 1) }
        distance?.getDoublePosition(2)?.let { move(it, 2) }
    }

    override fun move(distance: FloatArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun move(distance: DoubleArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun move(distance: Int, d: Int) {
        move( distance.toLong(), d )
    }

    override fun move(distance: Long, d: Int) {
        this.position = this.position + Vector3f().setComponent(d, distance.toFloat())
    }

    override fun move(distance: Localizable?) {
        distance?.getDoublePosition(0)?.let { move(it, 0) }
        distance?.getDoublePosition(1)?.let { move(it, 1) }
        distance?.getDoublePosition(2)?.let { move(it, 2) }
    }

    override fun move(distance: IntArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun move(distance: LongArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun numDimensions(): Int {
        return 3
    }

    override fun fwd(d: Int) {
        move( 1, d)
    }

    override fun getDoublePosition(d: Int): Double {
        return this.position[d].toDouble()
    }

    override fun setPosition(pos: RealLocalizable) {
        position.setComponent( 0, pos.getFloatPosition(0) )
        position.setComponent( 1, pos.getFloatPosition(1) )
        position.setComponent( 2, pos.getFloatPosition(2) )
    }

    override fun setPosition(pos: FloatArray?) {
        pos?.get(0)?.let { setPosition(it, 0 ) }
        pos?.get(1)?.let { setPosition(it, 1 ) }
        pos?.get(2)?.let { setPosition(it, 2 ) }
    }

    override fun setPosition(pos: DoubleArray?) {
        pos?.get(0)?.let { setPosition(it, 0 ) }
        pos?.get(1)?.let { setPosition(it, 1 ) }
        pos?.get(2)?.let { setPosition(it, 2 ) }
    }

    override fun setPosition(pos: Float, d: Int) {
        position.setComponent( d, pos )
    }

    override fun setPosition(pos: Double, d: Int) {
        setPosition( pos.toFloat(), d )
    }

    override fun setPosition(pos: Localizable?) {
        pos?.getIntPosition(0)?.let { setPosition(it, 0 ) }
        pos?.getIntPosition(1)?.let { setPosition(it, 1 ) }
        pos?.getIntPosition(2)?.let { setPosition(it, 2 ) }
    }

    override fun setPosition(pos: IntArray?) {
        pos?.get(0)?.let { setPosition(it, 0) }
        pos?.get(1)?.let { setPosition(it, 1) }
        pos?.get(2)?.let { setPosition(it, 2) }
    }

    override fun setPosition(pos: LongArray?) {
        pos?.get(0)?.let { setPosition(it, 0) }
        pos?.get(1)?.let { setPosition(it, 1) }
        pos?.get(2)?.let { setPosition(it, 2) }
    }

    override fun setPosition(position: Int, d: Int) {
        setPosition(position.toLong(), d)
    }

    override fun setPosition(position: Long, d: Int) {
        setPosition(position.toFloat(), d)
    }

}
