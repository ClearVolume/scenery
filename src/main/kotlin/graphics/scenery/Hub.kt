package graphics.scenery

import graphics.scenery.backends.Display
import graphics.scenery.backends.Renderer
import graphics.scenery.compute.OpenCLContext
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.TrackerInput
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.repl.REPL
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import java.util.concurrent.ConcurrentHashMap

/**
 * The Hub class interconnects the different components a scenery application may
 * have, such as a renderer or compute context (see also [SceneryElement].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class Hub(val name: String = "default") {
    private val logger by LazyLogger()
    /** Hash map storage for all the [SceneryElement] and their instances */
    val elements: ConcurrentHashMap<SceneryElement, Any> = ConcurrentHashMap()

    /**
     * Adds a given [SceneryElement] to the Hub.
     *
     * @param[type] The type of [SceneryElement] to add.
     * @param[obj] The [Hubable] object.
     */
    fun <T: Hubable> add(type: SceneryElement, obj: T): T {
        elements[type] = obj

        obj.hub = this

        return obj
    }

    /**
     * Shortcut function for [add] that automatically determines
     * the [SceneryElement] type.
     */
    fun <T: Hubable> add(obj: T): T {
        val type = when(obj) {
            is SceneryBase -> SceneryElement.Application
            is Renderer -> SceneryElement.Renderer
            is TrackerInput -> SceneryElement.HMDInput
            is OpenCLContext -> SceneryElement.OpenCLContext
            is InputHandler -> SceneryElement.Input
            is Statistics -> SceneryElement.Statistics
            is Settings -> SceneryElement.Settings
            is NodeSubscriber -> SceneryElement.NodeSubscriber
            is NodePublisher -> SceneryElement.NodePublisher
            is REPL -> SceneryElement.REPL
            else -> SceneryElement.Custom
        }

        return add(type, obj)
    }

    fun <T: Hubable> remove(obj: T) {
        val objs = elements.filter { it.value == obj }.keys
        objs.forEach { k -> elements.remove(k) }
    }

    /**
     * Adds a given [SceneryBase] instance to this Hub.
     *
     * @param[application] The [SceneryBase] instance to add to this Hub.
     */
    fun addApplication(application: SceneryBase): SceneryBase {
        elements[SceneryElement.Application] = application
        return application
    }

    /**
     * Returns a the basic application [SceneryBase] instance if contained in this Hub.
     *
     * @return [SceneryBase] instance, or null if not found.
     */
    fun getApplication(): SceneryBase? {
        return elements[SceneryElement.Application] as? SceneryBase
    }

    /**
     * Query the Hub for a given type of [SceneryElement]
     *
     * @param[type] [SceneryElement] type.
     * @return The instance of [SceneryElement] currently registered.
     */
    fun get(type: SceneryElement): Any? {
        return elements[type]
    }

    /**
     * Returns a [Hubable] element if it exists in this hub.
     */
    inline fun <reified T: Hubable> get(): T? {
        elements.values.forEach {
            if(it is T) {
                return it
            }
        }

        return null
    }

    /**
     * Query the Hub for a given type of [SceneryElement]
     *
     * @param[type] [SceneryElement] type.
     * @return The instance of [SceneryElement] currently registered.
     */
    fun <T: Hubable> get(type: SceneryElement): T? {
        return if(elements.containsKey(type)) {
            @Suppress("UNCHECKED_CAST")
            elements[type] as? T
        } else {
            null
        }
    }

    /**
     * Check whether the Hub has this type of [SceneryElement] registered
     *
     * @param[type] [SceneryElement] type to query for.
     * @return True if [type] is registered, else false.
     */
    fun has(type: SceneryElement): Boolean {
        return elements.containsKey(type)
    }

    /**
     * Returns a [TrackerInput] instance in case this Hub contains a [TrackerInput], which is working.
     *
     * @return A [TrackerInput] instance, or null if not found or not working.
     */
    fun getWorkingHMD(): TrackerInput? {
        return if (this.has(SceneryElement.HMDInput)
            && (this.get(SceneryElement.HMDInput) as TrackerInput).initializedAndWorking()) {
            this.get(SceneryElement.HMDInput) as? TrackerInput
        } else {
            null
        }
    }

    /**
     * Returns a [Display] in case this Hub contains an [SceneryElement.HMDInput] that can also
     * act as [Display] and is working.
     *
     * @return [Display] instance if found in the Hub, otherwise null.
     */
    fun getWorkingHMDDisplay(): Display? {
        return if (this.has(SceneryElement.HMDInput)
            && (this.get(SceneryElement.HMDInput) as Display).initializedAndWorking()) {
            this.get(SceneryElement.HMDInput) as? Display
        } else {
            null
        }
    }

    /**
     * Returns a string representation of the contents of this Hub.
     *
     * @return String representation of all the elements of the Hub, one per line.
     */
    @Suppress("unused")
    fun elementsAsString(): String {
        return "Hub (${elements.size}): " + elements.entries.joinToString(",") {
            "${it.key}=${it.value}"
        }
    }

    /**
     * Returns all Hub elements fitting the given [predicate].
     */
    fun find(predicate: (Any) -> Boolean): Map<SceneryElement, Any> {
        return elements.filter { predicate.invoke(it.value) }
    }

    /**
     * Returns a string representation of the Hub
     */
    override fun toString(): String {
        return elementsAsString()
    }
}
