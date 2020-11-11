package graphics.scenery.tests.examples.cluster

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.Light
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.volumes.Volume
import org.junit.Test
import org.scijava.ui.behaviour.ClickBehaviour
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Example to demonstrate rendering of volumetric data on a cluster
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ClusterExample: SceneryBase("Clustered Volume Rendering example") {
    var hmd: TrackedStereoGlasses? = null
    var publishedNodes = ArrayList<Node>()

    override fun init() {
        logger.warn("*** WARNING - EXPERIMENTAL ***")
        logger.warn("This is an experimental example, which might need additional configuration on your computer")
        logger.warn("or might not work at all. You have been warned!")

        hmd = hub.add(TrackedStereoGlasses("DTrack@10.1.2.201", screenConfig = "CAVEExample.yml"))
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 2560, 1600))

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            //position = Vector3f(.4f, .4f, 1.4f)
            position = Vector3f(.0f, -0.4f, 2.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val box = Box(Vector3f(2.0f, 2.0f, 2.0f))
        box.material.diffuse = Vector3f(1.0f, 0.0f, 0.0f)

        val shell = Box(Vector3f(120.0f, 120.0f, 120.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.Front
        shell.material.diffuse = Vector3f(0.0f, 0.0f, 0.0f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        scene.addChild(shell)

        val folder = Paths.get("M:/CAVE_DATA/histones-isonet/stacks/default/")
        val volume = Volume.fromPathRaw(folder, hub)

        with(volume) {
            volume.visible = true
            scene.addChild(this)
        }

        Light.createLightTetrahedron<PointLight>(spread = 4.0f, radius = 20.0f)
            .forEach { scene.addChild(it) }

        publishedNodes.add(cam)
//        publishedNodes.add(bileMesh)
//        publishedNodes.add(vasculature)
        publishedNodes.add(box)
        publishedNodes.add(volume)

        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

        publishedNodes.forEachIndexed { index, node ->
            publisher?.nodes?.put(13337 + index, node)

            subscriber?.nodes?.put(13337 + index, node)
        }

        val minDelay = 600

        if(publisher != null) {
            thread {
                while (!scene.initialized) {
                    Thread.sleep(1000)
                }

                while (true) {
                    val start = System.currentTimeMillis()

                    logger.info("Reading next volume...")
                    volume.nextTimepoint()

                    val timeToRead  = System.currentTimeMillis()-start

                    logger.info("took ${timeToRead} ms")
                    Thread.sleep(Math.max(0,minDelay-timeToRead))
                }
            }
        }
    }

    override fun inputSetup() {
        val inputHandler = hub.get(SceneryElement.Input) as InputHandler

        val cycleObjects = ClickBehaviour { _, _ ->
            val currentObject = publishedNodes.find { it.visible }
            val currentIndex = publishedNodes.indexOf(currentObject)

            publishedNodes.forEach { it.visible = false }
            publishedNodes[(currentIndex + 1) % (publishedNodes.size-1)].run {
                this.visible = true
                logger.info("Now visible: $this")
            }
        }

        inputHandler.addBehaviour("cycle_objects", cycleObjects)
        inputHandler.addKeyBinding("cycle_objects", "N")
    }

    @Test override fun main() {
        super.main()
    }
}
