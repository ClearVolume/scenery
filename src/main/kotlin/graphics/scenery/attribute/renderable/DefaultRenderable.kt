package graphics.scenery.attribute.renderable

import graphics.scenery.Node
import java.util.*
import kotlin.collections.HashMap

open class DefaultRenderable(override var parent: Node): Renderable {
    @Transient override var metadata: HashMap<String, Any> = HashMap()

    private var uuid: UUID = UUID.randomUUID()
    override fun getUuid(): UUID {
        return uuid
    }
    override var isBillboard: Boolean = false

}
