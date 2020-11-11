package graphics.scenery.mesh

import graphics.scenery.*
import graphics.scenery.backends.Shaders

/**
 * Class for rendering infinite planes.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class InfinitePlane : Mesh("InfinitePlane"), DisableFrustumCulling {
    /**
     * Enum class to define the grid type.
     */
    enum class Type { Grid, Checkerboard, Blueprint }

     /* Base line width for the grid. */
    @ShaderProperty
    var baseLineWidth = 0.65f

    /** [Type] of the plane, can be a normal grid, a blueprint grid, or a checkerboard. */
    @ShaderProperty
    var type = Type.Grid

    init {
        vertices = BufferUtils.allocateFloatAndPut(FloatArray(6 * 3))
        normals = BufferUtils.allocateFloatAndPut(FloatArray(6 * 3))
        texcoords = BufferUtils.allocateFloatAndPut(FloatArray(6 * 2))

        material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("InfiniteGrid.vert", "InfiniteGrid.frag")))
        material.blending.transparent = true
        material.blending.setOverlayBlending()
        material.cullingMode = Material.CullingMode.None
    }
}
