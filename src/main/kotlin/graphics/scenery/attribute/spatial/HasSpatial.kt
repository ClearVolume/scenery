package graphics.scenery.attribute.spatial

interface HasSpatial: HasCustomSpatial<Spatial> {
    override fun createSpatial(): Spatial {
        return DefaultSpatial(this)
    }
}

