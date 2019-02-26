package graphics.scenery

import graphics.scenery.backends.ShaderType
import graphics.scenery.backends.Shaders

/**
 * This class stores paths to GLSL shader files to be used for rendering preferentially,
 * as well as any parameters that are then expanded by the GLSL precompiler.
 *
 * @param[shaders]: The list of custom shaders to use as material
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ShaderMaterial(var shaders: Shaders) : Material() {
    /** Factory functions for ShaderMaterial */
    companion object {

        /** Creates a new file-based ShaderMaterial from a list of [files]. */
        @JvmStatic
        fun fromFiles(vararg files: String): ShaderMaterial {
            return ShaderMaterial(Shaders.ShadersFromFiles(files.toList().toTypedArray()))
        }

        /** Creates a new file-based ShaderMaterial the simpleName of the class [clazz]. */
        @JvmStatic
        @JvmOverloads
        fun fromClass(clazz: Class<*>, types: List<ShaderType> = listOf(ShaderType.VertexShader, ShaderType.FragmentShader)): ShaderMaterial {
            return ShaderMaterial(Shaders.ShadersFromClassName(clazz, types))
        }
    }
}
