package graphics.scenery.attribute.geometry

interface HasGeometry: HasCustomGeometry<Geometry> {
    override fun createGeometry(): Geometry {
        return DefaultGeometry(this)
    }
}
