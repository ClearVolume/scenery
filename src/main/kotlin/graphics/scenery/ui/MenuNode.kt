package graphics.scenery.ui

import graphics.scenery.Mesh
import graphics.scenery.RenderScissored
import graphics.scenery.ShaderProperty
import org.joml.Vector2f
import org.joml.Vector2i

class MenuNode(name: String) : Mesh(name), RenderScissored {
    override var offset: Vector2i = Vector2i(0)
    override var extent: Vector2i = Vector2i(0)

    @ShaderProperty
    var uScale: Vector2f = Vector2f(0.0f)

    @ShaderProperty
    var uTranslate: Vector2f = Vector2f(0.0f)
}