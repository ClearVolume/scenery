package graphics.scenery.primitives

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.fonts.SDFFontAtlas
import graphics.scenery.attribute.renderable.DefaultRenderable
import graphics.scenery.attribute.renderable.Renderable
import graphics.scenery.attribute.material.Material
import graphics.scenery.textures.Texture
import graphics.scenery.textures.Texture.RepeatMode
import org.joml.Vector2i
import org.joml.Vector3i
import org.joml.Vector4f

/**
 * TextBoard is a possibly billboarded display of a string of text,
 * rendered using signed-distance fields.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[font]  Name of the font to use for this text board
 * @property[isBillboard] Whether the board should be billboarded or not
 *
 * @constructor Returns a TextBoard instance, with [fontFamily] and a declared [ShaderMaterial]
 */
class TextBoard(font: String = "SourceSansPro-Regular.ttf", isBillboard: Boolean = false) : Mesh(),
    DisableFrustumCulling {

    /** The text displayed on this font board */
    var text: String = ""
        set(value) {
            if(value != field) {
                field = value

                needsPreUpdate = true
                geometry().dirty = true
            }
        }

    /** The font family of this font board. If reset, this will set the [dirty] flag,
     * such that the renderer can recreate the signed-distance fields used for displaying.
     *
     * If the name contains a dot (e.g. as in "Helvetica.ttf"), scenery will attempt to load
     * the font as a file from the class path.
     */
    var fontFamily: String = font
        set(value) {
            if(value != field) {
                field = value

                needsPreUpdate = true
                geometry().dirty = true
            }
        }

    /** The [ShaderProperty] storing whether the font board should be renderer transparently. */
    @ShaderProperty
    var transparent: Int = 1
    /** [ShaderProperty] to store the size of the used texture atlas storing the font's signed distance field */
    @ShaderProperty
    var atlasSize = Vector2i(1024, 1024)
    /** The [ShaderProperty] storing the font's color. */
    @ShaderProperty
    var fontColor: Vector4f = Vector4f(0.5f, 0.5f, 0.5f, 1.0f)
    /** The [ShaderProperty] storing the background color of the font board,
     * used only if [transparent] is 0. */
    @ShaderProperty
    var backgroundColor: Vector4f = Vector4f(1.0f, 1.0f, 1.0f, 1.0f)

    /** Flag to indicate whether the update routine should be called by the renderer */
    private var needsPreUpdate = true

    init {
        name = "TextBoard"
        fontFamily = font
        renderable {
            this.isBillboard = isBillboard
        }
        setMaterial(ShaderMaterial.fromFiles("DefaultForward.vert", "TextBoard.frag")) {
            blending.transparent = true
            blending.sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha
            blending.destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
            blending.sourceAlphaBlendFactor = Blending.BlendFactor.One
            blending.destinationAlphaBlendFactor = Blending.BlendFactor.Zero
            blending.colorBlending = Blending.BlendOp.add
            blending.alphaBlending = Blending.BlendOp.add
            cullingMode = Material.CullingMode.None
        }

        needsPreUpdate = true
    }
    override fun createRenderable(): Renderable {
        return object: DefaultRenderable(this) {

            val sdfCache = HashMap<String, SDFFontAtlas>()

            override fun preUpdate(renderer: Renderer, hub: Hub?) {
                if (!needsPreUpdate || hub == null) {
                    return
                }

                sdfCache.getOrPut(fontFamily,
                    {
                        SDFFontAtlas(
                            hub, fontFamily,
                            maxDistance = hub.get<Settings>(SceneryElement.Settings)?.get("sdf.MaxDistance") ?: 12
                        )
                    }).apply {


                    logger.debug("Updating mesh for text board {} to '{}'...", name, text)
                    val m = this.createMeshForString(text).geometry()

                    geometry {
                        vertices = m.vertices
                        normals = m.normals
                        indices = m.indices
                        texcoords = m.texcoords
                    }
                    atlasSize = Vector2i(this.atlasWidth, this.atlasHeight)

                    material().textures["diffuse"] = Texture(
                        Vector3i(atlasSize.x(), atlasSize.y(), 1),
                        channels = 1, contents = this.getAtlas(),
                        repeatUVW = RepeatMode.ClampToBorder.all(),
                        normalized = true,
                        mipmap = true
                    )

                    needsPreUpdate = false
                }
            }

            /** Stringify the font board. Returns [fontFamily] used as well as the [text]. */
            override fun toString(): String {
                return "TextBoard ($fontFamily): $text"
            }
        }
    }
}
