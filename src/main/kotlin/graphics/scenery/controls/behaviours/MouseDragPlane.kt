/*-
 * #%L
 * Scenery-backed 3D visualization package for ImageJ.
 * %%
 * Copyright (C) 2016 - 2020 SciView developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package graphics.scenery.controls.behaviours

import graphics.scenery.BoundingGrid
import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.utils.LazyLogger
import org.joml.Vector3f
import org.scijava.ui.behaviour.DragBehaviour
import org.scijava.ui.behaviour.ScrollBehaviour
import kotlin.reflect.KProperty

/**
 * Drag nodes along the viewplane by mouse.
 *
 * @author Kyle Harrington
 * @author Jan Tiemann
 */
open class MouseDragPlane(
    protected val name: String,
    camera: () -> Camera?,
    protected val alternativeTargetNode: (() -> Node?)? = null,
    protected var debugRaycast: Boolean = false,
    protected var ignoredObjects: List<Class<*>> = listOf<Class<*>>(BoundingGrid::class.java),
    protected val mouseSpeed: () -> Float = { 0.25f },
    protected val fpsSpeedSlow: () -> Float = { 0.05f }
) : DragBehaviour, ScrollBehaviour, WithCameraDelegateBase(camera) {

    protected val logger by LazyLogger()

    protected var currentNode: Node? = null
    private var lastX = 0
    private var lastY = 0


    /**
     * This function is called upon mouse down and initializes the camera control
     * with the current window size.
     *
     * x position in window
     * y position in window
     */
    override fun init(x: Int, y: Int) {
        if (alternativeTargetNode != null) {
            currentNode = alternativeTargetNode.invoke()
        } else {
            cam?.let { cam ->
                val matches = cam.getNodesForScreenSpacePosition(x, y, ignoredObjects, debugRaycast)
                currentNode = matches.matches.firstOrNull()?.node
            }
        }

        lastX = x
        lastY = y
    }

    override fun drag(x: Int, y: Int) {
        val targetedNode = currentNode

        cam?.let {
            if (targetedNode == null || !targetedNode.lock.tryLock()) return

            targetedNode.ifSpatial {
                it.right.mul((x - lastX) * fpsSpeedSlow() * mouseSpeed(), dragPosUpdater)
                position.add(dragPosUpdater)
                it.up.mul((lastY - y) * fpsSpeedSlow() * mouseSpeed(), dragPosUpdater)
                position.add(dragPosUpdater)
                needsUpdate = true
            }

            targetedNode.lock.unlock()

            lastX = x
            lastY = y
        }
    }

    override fun end(x: Int, y: Int) {
        // intentionally empty. A new click will overwrite the running variables.
    }

    override fun scroll(wheelRotation: Double, isHorizontal: Boolean, x: Int, y: Int) {
        val targetedNode = currentNode

        cam?.let {
            if (targetedNode == null || !targetedNode.lock.tryLock()) return

            it.forward.mul(
                wheelRotation.toFloat() * fpsSpeedSlow() * mouseSpeed(),
                scrollPosUpdater
            )
            targetedNode.ifSpatial {
                position.add(scrollPosUpdater)
                needsUpdate = true
            }

            targetedNode.lock.unlock()
        }
    }

    //aux vars to prevent from re-creating them over and over
    private val dragPosUpdater: Vector3f = Vector3f()
    private val scrollPosUpdater: Vector3f = Vector3f()
}
