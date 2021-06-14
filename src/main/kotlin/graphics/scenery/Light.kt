package graphics.scenery

import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.joml.Vector3f
import kotlin.math.sqrt
import kotlin.reflect.full.createInstance

/**
 * Abstract class for light [Node]s.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
abstract class Light(name: String = "Light") : Mesh(name) {

    /** Enum class to determine light type. */
    enum class LightType {
        PointLight,
        DirectionalLight
    }

    /** Emission color of the light. */
    @ShaderProperty
    abstract var emissionColor: Vector3f

    /** Intensity of the light. */
    @ShaderProperty
    abstract var intensity: Float

    /** The light's type, stored as [LightType]. */
    @ShaderProperty
    abstract val lightType: LightType

    companion object {
        /**
         * Creates a tetrahedron lights of type [T] and returns them as a list.
         * The [center] of the tetrahedron can be specified, as can be the [spread] of it, the light's [intensity], [color], and [radius].
         */
        inline fun <reified T: Light> createLightTetrahedron(center: Vector3f = Vector3f(0.0f), spread: Float = 1.0f, intensity: Float = 1.0f, color: Vector3f = Vector3f(1.0f), radius: Float =  5.0f): List<T> {
            val tetrahedron = listOf(
                Vector3f(1.0f, 0f, -1.0f/ sqrt(2.0).toFloat()) * spread,
                Vector3f(-1.0f,0f,-1.0f/ sqrt(2.0).toFloat()) * spread,
                Vector3f(0.0f,1.0f,1.0f/ sqrt(2.0).toFloat()) * spread,
                Vector3f(0.0f,-1.0f,1.0f/ sqrt(2.0).toFloat()) * spread)

            return tetrahedron.map { position ->
                val light = T::class.createInstance()
                light.spatial {
                    this.position = position + center
                }
                light.emissionColor = color
                light.intensity = intensity
                if(light is PointLight) {
                    light.lightRadius = radius
                }
                light
            }
        }
    }
}
