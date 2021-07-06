package graphics.scenery.utils

import java.util.*

/**
 * Ring buffer class. Creates a ring buffer of size [size], with all elements
 * initialiased to a default value. Querying this ring buffer will return the current
 * element of the buffer, and advance the read position. Setting it will store
 * the element, and advance the write position. Running one of these two operations
 * again will then affect the next element of the ring buffer.
 */
open class RingBuffer<T: Any>(var size: Int, default: ((Int) -> T)? = null, protected val cleanup: ((T) -> Any)? = null) {

    protected var backingStore: ArrayList<T> = ArrayList(size)

    /** The current read position of the ring buffer */
    var currentReadPosition = 0
        protected set
    /** The current write position of the ring buffer */
    var currentWritePosition = 0
        protected set

    init {
        default?.let {
            for (element in 0 until size) {
                put(default.invoke(element))
            }
        }
    }

    /**
     * Puts a new [element] into the ring buffer, advancing the
     * write position.
     */
    fun put(element: T) {
        if(backingStore.size <= size) {
            backingStore.add(element)
        } else {
            currentWritePosition = currentWritePosition.rem(backingStore.size)
            backingStore[currentWritePosition] = element
        }

        currentWritePosition++
    }

    /**
     * Retrieves and returns the current element from the ring buffer, and advances
     * the current read position.
     */
    fun get(): T {
        currentReadPosition = currentReadPosition.rem(backingStore.size)
        val element = backingStore[currentReadPosition]

        currentReadPosition++
        return element
    }

    /**
     * Resets the ring buffer, and clears its backing store.
     */
    fun reset() {
        currentReadPosition = 0
        currentWritePosition = 0

        backingStore.clear()
    }

    /**
     * Closes the ring buffer, invoking cleanup on its elements.
     */
    fun close() {
        backingStore.forEach {
            cleanup?.invoke(it)
        }
        backingStore.clear()
    }
}
