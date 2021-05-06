package graphics.scenery.tests.examples.cluster

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.joml.Vector3f
import org.scijava.ui.behaviour.ClickBehaviour
import java.nio.file.Paths

/**
 * Demo reel example to be run on a CAVE system.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class DemoReelExample: SceneryBase("Demo Reel") {
    var hmd: TrackedStereoGlasses? = null
    var publishedNodes = ArrayList<Node>()

    var cam = DetachedHeadCamera()
    var bileScene = Mesh(name = "bile")
    var histoneScene = Mesh(name = "histone")
    var drosophilaScene = Mesh(name = "drosophila")
    var retinaScene = Mesh(name = "retina")

    val scenes = listOf(bileScene, histoneScene, drosophilaScene, retinaScene)

    override fun init() {
        logger.warn("*** WARNING - EXPERIMENTAL ***")
        logger.warn("This is an experimental example, which might need additional configuration on your computer")
        logger.warn("or might not work at all. You have been warned!")

        hmd = hub.add(TrackedStereoGlasses("DTrack@10.1.2.201", screenConfig = "CAVEExample.yml"))
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, 2560, 1600))

        cam = DetachedHeadCamera(hmd)
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 55.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight, 0.02f, 500.0f)
            disableCulling = true

            scene.addChild(this)
        }

        // box setup
        val shell = Box(Vector3f(120.0f, 120.0f, 120.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.Front
        shell.material.diffuse = Vector3f(0.0f, 0.0f, 0.0f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        scene.addChild(shell)

        Light.createLightTetrahedron<PointLight>(spread = 50.0f, intensity = 150.0f, radius = 150.0f)
            .forEach { scene.addChild(it) }

        // scene setup
        val driveLetter = System.getProperty("scenery.DriveLetter", "E")

        val histoneVolume = Volume.fromPathRaw(
            Paths.get("$driveLetter:/ssd-backup-inauguration/CAVE_DATA/histones-isonet/stacks/default/"),
            hub
        )
        histoneVolume.transferFunction = TransferFunction.ramp(0.1f, 1.0f)
        histoneVolume.colormap = Colormap.get("hot")
        histoneScene.addChild(histoneVolume)
        histoneScene.visible = false
        scene.addChild(histoneScene)

        val drosophilaVolume = Volume.fromPathRaw(
            Paths.get("$driveLetter:/ssd-backup-inauguration/CAVE_DATA/droso-royer-autopilot-transposed/"),
            hub
        )
        drosophilaVolume.rotation.rotateX(1.57f)
        drosophilaVolume.transferFunction = TransferFunction.ramp(0.1f, 1.0f)
        drosophilaVolume.colormap = Colormap.get("hot")
        drosophilaScene.addChild(drosophilaVolume)
        drosophilaScene.visible = false
        scene.addChild(drosophilaScene)

        val retinaVolume = Volume.fromPathRaw(
            Paths.get("$driveLetter:/ssd-backup-inauguration/CAVE_DATA/retina_test2/"),
            hub
        )
        retinaScene.addChild(retinaVolume)
        retinaScene.visible = false
        scene.addChild(retinaScene)

        val bile = Mesh()
        val canaliculi = Mesh()
        canaliculi.readFrom("$driveLetter:/ssd-backup-inauguration/meshes/bile-canaliculi.obj")
        canaliculi.scale = Vector3f(0.1f, 0.1f, 0.1f)
        canaliculi.position = Vector3f(-80.0f, -60.0f, 10.0f)
        canaliculi.material.diffuse = Vector3f(0.5f, 0.7f, 0.1f)
        bile.addChild(canaliculi)

        val nuclei = Mesh()
        nuclei.readFrom("$driveLetter:/ssd-backup-inauguration/meshes/bile-nuclei.obj")
        nuclei.scale = Vector3f(0.1f, 0.1f, 0.1f)
        nuclei.position = Vector3f(-80.0f, -60.0f, 10.0f)
        nuclei.material.diffuse = Vector3f(0.8f, 0.8f, 0.8f)
        bile.addChild(nuclei)

        val sinusoidal = Mesh()
        sinusoidal.readFrom("$driveLetter:/ssd-backup-inauguration/meshes/bile-sinus.obj")
        sinusoidal.scale = Vector3f(0.1f, 0.1f, 0.1f)
        sinusoidal.position = Vector3f(-80.0f, -60.0f, 10.0f)
        sinusoidal.material.ambient = Vector3f(0.1f, 0.0f, 0.0f)
        sinusoidal.material.diffuse = Vector3f(0.4f, 0.0f, 0.02f)
        sinusoidal.material.specular = Vector3f(0.05f, 0f, 0f)
        bile.addChild(sinusoidal)
        bileScene.addChild(bile)
        scene.addChild(bileScene)

        publishedNodes.add(cam)
        publishedNodes.add(drosophilaVolume)
        publishedNodes.add(drosophilaScene)

        publishedNodes.add(histoneVolume)
        publishedNodes.add(histoneScene)

        publishedNodes.add(bile)
        publishedNodes.add(canaliculi)
        publishedNodes.add(nuclei)
        publishedNodes.add(sinusoidal)
        publishedNodes.add(bileScene)

        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

        publishedNodes.forEachIndexed { index, node ->
            publisher?.nodes?.put(13337 + index, node)

            subscriber?.nodes?.put(13337 + index, node)
        }

        val minDelay = 200

        logger.info("Publisher is: $publisher")
        if(publisher != null) {
            animate {
                while (!scene.initialized) {
                    logger.info("Wainting for scene init")
                    Thread.sleep(1000)
                }

                while (true) {
                    var sleepDuration = 50L

                    arrayOf(drosophilaScene, histoneScene).forEach {
                        if(it.visible) {
                            logger.info("Reading next volume for ${it.name} ...")
                            val start = System.currentTimeMillis()

                            val v = it.children[0]
                            (v as? Volume)?.nextTimepoint()

                            val timeToRead  = System.currentTimeMillis() - start

                            if(it.name == "drosophila") {
                                sleepDuration = Math.max(40,minDelay-timeToRead)
                            }

                            if(it.name == "histone") {
                                sleepDuration = Math.max(30,minDelay-timeToRead)
                            }
                        }
                    }

                    Thread.sleep(sleepDuration)
                }
            }
        }
    }

    /**
     * Standard input setup, plus additional key bindings to
     * switch scenes.
     */
    override fun inputSetup() {
        val inputHandler = (hub.get(SceneryElement.Input) as? InputHandler) ?: return

        fun gotoScene(sceneName: String) = ClickBehaviour { _, _ ->
            scenes.filter { it.name == sceneName }.forEach { scene -> scene.runRecursive { it.visible = true } }
            scenes.filter { it.name != sceneName }.forEach { scene -> scene.runRecursive { it.visible = false } }

            scene.findObserver()?.position = Vector3f(0.0f, 0.0f, 3.0f)
        }

        inputHandler.addBehaviour("goto_scene_bile", gotoScene("bile"))
        inputHandler.addBehaviour("goto_scene_histone", gotoScene("histone"))
        inputHandler.addBehaviour("goto_scene_drosophila", gotoScene("drosophila"))
        inputHandler.addBehaviour("goto_scene_retina", gotoScene("retina"))


        inputHandler.addKeyBinding("goto_scene_bile", "shift 1")
        inputHandler.addKeyBinding("goto_scene_histone", "shift 2")
        inputHandler.addKeyBinding("goto_scene_drosophila", "shift 3")
        inputHandler.addKeyBinding("goto_scene_retina", "shift 4")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DemoReelExample().main()
        }
    }
}
