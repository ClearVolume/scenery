package graphics.scenery.volumes

import graphics.scenery.Hub
import graphics.scenery.OrientedBoundingBox
import graphics.scenery.Origin
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.times
import org.joml.Matrix4f
import org.joml.Vector3f
import tpietzsch.example2.VolumeViewerOptions

class RAIVolume(val ds: VolumeDataSource.RAISource<*>, options: VolumeViewerOptions, hub: Hub): Volume(ds, options, hub) {
    init {
        name = "Volume (RAI source)"
        if(ds.cacheControl != null) {
            logger.debug("Adding cache control")
            cacheControls.addCacheControl(ds.cacheControl)
        }

        timepointCount = ds.numTimepoints

        boundingBox = generateBoundingBox()
    }

    override fun generateBoundingBox(): OrientedBoundingBox {
        val source = ds.sources.firstOrNull()

        val sizes = if(source != null) {
            val s = source.spimSource.getSource(0, 0)
            val min = Vector3f(s.min(0).toFloat(), s.min(1).toFloat(), s.min(2).toFloat())
            val max = Vector3f(s.max(0).toFloat(), s.max(1).toFloat(), s.max(2).toFloat())
            max - min
        } else {
            Vector3f(1.0f, 1.0f, 1.0f)
        }

        return OrientedBoundingBox(this,
            Vector3f(-0.0f, -0.0f, -0.0f),
            sizes)
    }

    override fun localScale(): Vector3f {
        var size = Vector3f(1.0f, 1.0f, 1.0f)
        val source = ds.sources.firstOrNull()

        if(source != null) {
            val s = source.spimSource.getSource(0, 0)
            val min = Vector3f(s.min(0).toFloat(), s.min(1).toFloat(), s.min(2).toFloat())
            val max = Vector3f(s.max(0).toFloat(), s.max(1).toFloat(), s.max(2).toFloat())
            size = max - min
        }
        logger.debug("Sizes are $size")

        return Vector3f(
                size.x() * pixelToWorldRatio / 10.0f,
                size.y() * pixelToWorldRatio / 10.0f,
                size.z() * pixelToWorldRatio / 10.0f
        )
    }

    override fun createSpatial(): VolumeSpatial {
        return object: VolumeSpatial(this) {
            override fun composeModel() {
                @Suppress("SENSELESS_COMPARISON")
                if (position != null && rotation != null && scale != null) {
                    val source = ds.sources.firstOrNull()

                    val shift = if (source != null) {
                        val s = source.spimSource.getSource(0, 0)
                        val min = Vector3f(s.min(0).toFloat(), s.min(1).toFloat(), s.min(2).toFloat())
                        val max = Vector3f(s.max(0).toFloat(), s.max(1).toFloat(), s.max(2).toFloat())
                        (max - min) * (-0.5f)
                    } else {
                        Vector3f(0.0f, 0.0f, 0.0f)
                    }

                    model.translation(position)
                    model.mul(Matrix4f().set(this.rotation))
                    model.scale(scale)
                    model.scale(localScale())
                    if (origin == Origin.Center) {
                        model.translate(shift)
                    }
                }
            }
        }
    }
}
