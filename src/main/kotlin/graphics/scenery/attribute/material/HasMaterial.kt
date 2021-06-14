package graphics.scenery.attribute.material

interface HasMaterial: HasCustomMaterial<Material> {
    override fun createMaterial(): Material {
        return DefaultMaterial()
    }
}

