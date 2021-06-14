package graphics.scenery.attribute

interface AttributesMap {
    fun <T, U: T> put(attributeClass: Class<T>, attribute: U)
    fun <T, U: T> get(attributeClass: Class<T>): U
    fun <T, U> remove(attributeClass: Class<T>): U
}
