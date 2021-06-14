package graphics.scenery.attribute.renderable

interface HasRenderable: HasCustomRenderable<Renderable> {
    override fun createRenderable(): Renderable {
        return DefaultRenderable(this)
    }
}

