package graphics.scenery.attribute.material

import graphics.scenery.Blending
import graphics.scenery.attribute.material.Material.CullingMode.*
import graphics.scenery.textures.Texture
import graphics.scenery.utils.TimestampedConcurrentHashMap
import org.joml.Vector3f

/**
 * Material interface, storing material colors, textures, opacity properties, etc.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
interface Material {

    /**
     * Culling Mode enum, to determine which faces are culling when assuming CCW order
     * [Front] - front faces culled
     * [Back] - back faces culled
     * [FrontAndBack] - all faces culled
     * [None] - no faces culled
     */
    enum class CullingMode { None, Front, Back, FrontAndBack }

    /** Depth test enum, determines which operation on the depth buffer values results in a pass. */
    enum class DepthTest { Less, Greater, LessEqual, GreaterEqual, Always, Never, Equal }

    /** Name of the material. */
    var name: String
    /** Diffuse color of the material. */
    var diffuse: Vector3f
    /** Specular color of the material. */
    var specular: Vector3f
    /** Ambient color of the material. */
    var ambient: Vector3f
    /** Specular exponent */
    var roughness: Float
    /** Metallicity, 0.0 is non-metal, 1.0 is full metal */
    var metallic: Float

    /** Blending settings for this material. See [Blending]. */
    var blending: Blending

    /** Hash map storing the type and origin of the material's textures. Key is the
     * type, e.g. ("diffuse", "normal", "displacement"...), value can be a file path or
     * via "fromBuffer:[transferTextureName], a named [Texture] in [transferTextures]. */
    var textures: TimestampedConcurrentHashMap<String, Texture>

    /** Culling mode of the material. @see[CullingMode] */
    var cullingMode: CullingMode

    /** depth testing mode for this material */
    var depthTest: DepthTest

    /** Flag to make the object wireframe */
    var wireframe: Boolean

    /**
     * Returns a hash of the material, with properties relevant for
     * presentation taken into account. Does not include [textures], as
     * these are timetamped now.
     */
    fun materialHashCode() : Int {
        var result = blending.hashCode()
        result = 31 * result + cullingMode.hashCode()
        result = 31 * result + depthTest.hashCode()
        result = 31 * result + wireframe.hashCode()
        return result
    }
}
