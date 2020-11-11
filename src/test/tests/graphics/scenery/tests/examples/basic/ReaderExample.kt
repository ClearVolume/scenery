package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.ControllerDrag
import graphics.scenery.mesh.BoundingGrid
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.Mesh
import graphics.scenery.mesh.MeshImporter
import graphics.scenery.numerics.Random
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Volume
import org.junit.Test
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.streams.toList

/**
 * Example for loading geometry and volumetric files.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ReaderExample : SceneryBase("ReaderExample", 1280, 720) {
    var hmd: OpenVRHMD? = null
    var loadedFilename: String? = null
    lateinit var loadedObject: Node

    var playing = false
    var delay = 500L

    override fun init() {
        val files = ArrayList<String>()

        val c = Context()
        val ui = c.getService(UIService::class.java)
        val file = ui.chooseFile(null, FileWidget.OPEN_STYLE)
        file?.let { files.add(it.absolutePath) }

        val cam = DetachedHeadCamera()
        hmd = try {
            OpenVRHMD()
        } catch (e: Exception) {
            null
        }

        hmd?.let {
            hub.add(it)
            cam.tracker = it
        }

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        renderer?.pushMode = true

        val b = Box(Vector3f(50.0f, 0.2f, 50.0f))
        b.position = Vector3f(0.0f, -1.0f, 0.0f)
        b.material.diffuse = Vector3f(0.1f, 0.1f, 0.1f)
        scene.addChild(b)

        val tetrahedron = listOf(
            Vector3f(1.0f, 0f, -1.0f/ sqrt(2.0).toFloat()),
            Vector3f(-1.0f,0f,-1.0f/ sqrt(2.0).toFloat()),
            Vector3f(0.0f,1.0f,1.0f/ sqrt(2.0).toFloat()),
            Vector3f(0.0f,-1.0f,1.0f/ sqrt(2.0).toFloat()))

        val lights = (0 until 4).map { PointLight(radius = 50.0f) }

        loadedObject = if(files.isNotEmpty()) {
            when {
                files.first().endsWith(".tiff") || files.first().endsWith(".tif") || files.first().endsWith(".raw")  -> {
                    Volume.fromPath(Paths.get(files.first()), hub)
                }

                else -> MeshImporter.readFrom(files.first())
            }
        } else {
            logger.warn("No file selected, returning empty node.")
            Node("empty")
        }

        loadedFilename = files.firstOrNull()
        loadedObject.fitInto(6.0f, scaleUp = false)

        scene.addChild(loadedObject)

        val bg = BoundingGrid()
        bg.node = loadedObject

        tetrahedron.mapIndexed { i, position ->
            lights[i].position = position * 5.0f
            lights[i].emissionColor = Random.random3DVectorFromRange(0.8f, 1.0f)
            lights[i].intensity = 0.5f
            scene.addChild(lights[i])
        }

        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        thread {
            while(!loadedObject.initialized) {
                Thread.sleep(200)
            }

            loadedObject.putAbove(Vector3f(0.0f, -0.3f, 0.0f))

            hmd?.events?.onDeviceConnect?.add { hmd, device, timestamp ->
                if(device.type == TrackedDeviceType.Controller) {
                    logger.info("Got device ${device.name} at $timestamp")
                    device.model?.let { hmd.attachToNode(device, it, cam) }
                }
            }
        }
    }

    override fun inputSetup() {
        val cam = scene.findObserver() ?: throw IllegalStateException("Could not find camera")
        val hmd = this.hmd ?: return

        inputHandler?.addBehaviour("nextObject", ClickBehaviour { _, _ -> loadNext(forward = true) })
        inputHandler?.addBehaviour("previousObject", ClickBehaviour { _, _ -> loadNext(forward = false) })
        inputHandler?.addKeyBinding("nextObject", "shift M")
        inputHandler?.addKeyBinding("previousObject", "shift N")

        inputHandler?.let { handler ->
            hashMapOf(
                "move_forward_fast" to "K",
                "move_back_fast" to "J",
                "move_left_fast" to "H",
                "move_right_fast" to "L").forEach { (name, key) ->
                handler.getBehaviour(name)?.let { b ->
                    hmd.addBehaviour(name, b)
                    hmd.addKeyBinding(name, key)
                }
            }
        }

        val nextTimepoint = ClickBehaviour { _, _ ->
            thread {
                loadNext(true)
            }
        }

        val prevTimepoint = ClickBehaviour { _, _ ->
            thread {
                loadNext(false)
            }
        }

        hmd.addBehaviour("skip_to_next", nextTimepoint)
        hmd.addKeyBinding("skip_to_next", "D")
        hmd.addBehaviour("skip_to_prev", prevTimepoint)
        hmd.addKeyBinding("skip_to_prev", "A")

        val scaleFactor = 1.2f
        val fasterOrScale = ClickBehaviour { _, _ ->
            if(playing) {
                delay = minOf((delay / scaleFactor).toLong(), 2000L)
                cam.showMessage("Speed: ${String.format("%.2f", (1000f/delay.toFloat()))} vol/s")
            } else {
                val scale = minOf(loadedObject.scale.x() * 1.2f, 3.0f)
                loadedObject.scale = Vector3f(1.0f) * scale
            }
        }

        hmd.addBehaviour("faster_or_scale", fasterOrScale)
        hmd.addKeyBinding("faster_or_scale", "W")

        val slowerOrScale = ClickBehaviour { _, _ ->
            if(playing) {
                delay = maxOf((delay * scaleFactor).toLong(), 5L)
                cam.showMessage("Speed: ${String.format("%.2f", (1000f/delay.toFloat()))} vol/s")
            } else {
                val scale = maxOf(loadedObject.scale.x() / 1.2f, 0.1f)
                loadedObject.scale = Vector3f(1.0f) * scale
            }
        }

        hmd.addBehaviour("slower_or_scale", slowerOrScale)
        hmd.addKeyBinding("slower_or_scale", "S")

        val playPause = ClickBehaviour { _, _ ->
            playing = !playing
            if(playing) {
                cam.showMessage("Paused")
            } else {
                cam.showMessage("Playing")
            }
        }

        hmd.addBehaviour("play_pause", playPause)
        hmd.addKeyBinding("play_pause", "M")

        val move = ControllerDrag(
            TrackerRole.RightHand,
            hmd,
            trackPosition = true,
            trackRotation = true
        ) { loadedObject }

        hmd.addBehaviour("trigger_move", move)
        hmd.addKeyBinding("trigger_move", "U")

        hmd.allowRepeats += OpenVRHMD.OpenVRButton.Trigger to TrackerRole.RightHand
    }

    /**
     * Loads the next dataset with the same extension from the directory the current
     * dataset resides in. If [forward] is true, the direction is forward, otherwise backwards.
     */
    private fun loadNext(forward: Boolean = true) {
        val name = loadedFilename ?: return
        val extension = name.substringAfterLast(".").toLowerCase()
        val current = Paths.get(name)

        val direction = if(forward) {
            1
        } else {
            -1
        }

        logger.info("Getting next for $current with $extension in directory ${current.parent}")
        val files = Files.list(current.parent).toList().filter { it.toFile().absolutePath.endsWith(extension) }
        logger.info("Files are ${files.joinToString()}")
        val currentIndex = files.indexOf(current)
        val newIndex = if(currentIndex + direction < 0) {
            files.size - 1
        } else {
            (currentIndex + direction) % files.size
        }
        val file = files[newIndex]


        when(extension) {
            "obj", "stl" -> {
                loadedObject = MeshImporter.readFrom(file.toFile().absolutePath)
                loadedObject.centerOn(Vector3f(0.0f))
                loadedObject.fitInto(6.0f, scaleUp = false)
            }
        }

        loadedFilename = file.toFile().absolutePath
    }

    @Test override fun main() {
        super.main()
    }
}
