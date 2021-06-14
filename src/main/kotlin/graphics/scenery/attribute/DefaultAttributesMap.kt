package graphics.scenery.attribute

class DefaultAttributesMap: AttributesMap {
    private val attributes = HashMap<Class<*>, Any>()
    override fun <T, U: T> put(attributeClass: Class<T>, attribute: U) {
        this.attributes.put(attributeClass, attribute as Any)
    }
    override fun <T, U: T> get(attributeClass: Class<T>): U {
        @Suppress("UNCHECKED_CAST")
        return this.attributes.get(attributeClass) as U
    }
    override fun <T, U> remove(attributeClass: Class<T>): U {
        @Suppress("UNCHECKED_CAST")
        return this.attributes.remove(attributeClass) as U
    }
}
