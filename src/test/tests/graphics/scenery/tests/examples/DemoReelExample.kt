package graphics.scenery.tests.examples

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.controls.behaviours.ArcballCameraControl
import graphics.scenery.controls.behaviours.FPSCameraControl
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.volumes.DirectVolumeFullscreen
import org.junit.Test
import org.scijava.ui.behaviour.ClickBehaviour
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class DemoReelExample: SceneryDefaultApplication("Demo Reel") {
    var hmd: TrackedStereoGlasses? = null
    var publishedNodes = ArrayList<Node>()

    var cam = DetachedHeadCamera()
    var bileScene = Mesh(name = "bile")
    var histoneScene = Mesh(name = "histone")
    var drosophilaScene = Mesh(name = "drosophila")
    var retinaScene = Mesh(name = "retina")

    lateinit var goto_scene_bile: ClickBehaviour
    lateinit var goto_scene_drosophila: ClickBehaviour
    lateinit var goto_scene_histone: ClickBehaviour
    lateinit var goto_scene_retina: ClickBehaviour

    override fun init() {
        hmd = TrackedStereoGlasses("DTrack@10.1.2.201", screenConfig = "CAVEExample.yml")
        hub.add(SceneryElement.HMDInput, hmd!!)

        renderer = Renderer.createRenderer(hub, applicationName, scene, 2560, 1600)
        hub.add(SceneryElement.Renderer, renderer!!)

        cam = DetachedHeadCamera(hmd)
        with(cam) {
//            position = GLVector(0.0f, -1.3190879f, 0.8841703f)
            position = GLVector(0.0f, 0.0f, 55.0f)
            perspectiveCamera(50.0f, 1.0f*windowWidth, 1.0f*windowHeight)
            active = true

            scene.addChild(this)
        }

        // box setup

        val shell = Box(GLVector(120.0f, 120.0f, 120.0f), insideNormals = true)
        shell.material.doubleSided = true
        shell.material.diffuse = GLVector(0.0f, 0.0f, 0.0f)
        shell.material.specular = GLVector.getNullVector(3)
        shell.material.ambient = GLVector.getNullVector(3)
        scene.addChild(shell)

        // lighting setup

        val lights = (0..4).map {
            PointLight()
        }

        val tetrahedron = listOf(
            GLVector(1.0f, 0f, -1.0f/Math.sqrt(2.0).toFloat()),
            GLVector(-1.0f,0f,-1.0f/Math.sqrt(2.0).toFloat()),
            GLVector(0.0f,1.0f,1.0f/Math.sqrt(2.0).toFloat()),
            GLVector(0.0f,-1.0f,1.0f/Math.sqrt(2.0).toFloat()))

        tetrahedron.mapIndexed { i, position ->
            lights[i].position = position * 50.0f
            lights[i].emissionColor = GLVector(1.0f, 0.5f,0.3f) //Numerics.randomVectorFromRange(3, 0.2f, 0.8f)
            lights[i].intensity = 200.2f
            lights[i].linear = 0.0f
            lights[i].quadratic = 0.7f
            scene.addChild(lights[i])
        }

        // scene setup
        val driveLetter = System.getProperty("scenery.DriveLetter", "E")
        val volumes = HashMap<String, List<String>>()
        volumes.put(histoneScene.name, getVolumes("$driveLetter:/ssd-backup-inauguration/CAVE_DATA/histones-isonet/stacks/default/"))
        volumes.put(retinaScene.name, getVolumes("$driveLetter:/ssd-backup-inauguration/CAVE_DATA/retina_test2/"))
        volumes.put(drosophilaScene.name, getVolumes("$driveLetter:/ssd-backup-inauguration/CAVE_DATA/droso-royer-autopilot-transposed/"))

        val histoneVolume = DirectVolumeFullscreen(autosetProperties = false)
        //histoneVolume.
        histoneScene.addChild(histoneVolume)
        histoneScene.visible = false
        scene.addChild(histoneScene)

        val drosophilaVolume = DirectVolumeFullscreen(autosetProperties = false)
        drosophilaVolume.rotation.rotateByAngleX(1.57f)
        drosophilaScene.addChild(drosophilaVolume)
        drosophilaScene.visible = false
        scene.addChild(drosophilaScene)

        val retinaVolume = DirectVolumeFullscreen(autosetProperties = false)
        //retinaVolume.rotation.rotateByAngleX(1.57f)
        retinaScene.addChild(retinaVolume)
        retinaScene.visible = false
        scene.addChild(retinaScene)

        val bile = Mesh()
        val canaliculi = Mesh()
        canaliculi.readFrom("$driveLetter:/ssd-backup-inauguration/meshes/bile-canaliculi.obj")
        canaliculi.position = GLVector(-600.0f, -800.0f, -20.0f)
        canaliculi.scale = GLVector(0.1f, 0.1f, 0.1f)
        canaliculi.material.diffuse = GLVector(0.5f, 0.7f, 0.1f)
        bile.addChild(canaliculi)

        val nuclei = Mesh()
        nuclei.readFrom("$driveLetter:/ssd-backup-inauguration/meshes/bile-nuclei.obj")
        nuclei.position = GLVector(-600.0f, -800.0f, -20.0f)
        nuclei.scale = GLVector(0.1f, 0.1f, 0.1f)
        nuclei.material.diffuse = GLVector(0.8f, 0.8f, 0.8f)
        bile.addChild(nuclei)

        val sinusoidal = Mesh()
        sinusoidal.readFrom("$driveLetter:/ssd-backup-inauguration/meshes/bile-sinus.obj")
        sinusoidal.position = GLVector(-600.0f, -800.0f, -20.0f)
        sinusoidal.scale = GLVector(0.1f, 0.1f, 0.1f)
        sinusoidal.material.ambient = GLVector(0.1f, 0.0f, 0.0f)
        sinusoidal.material.diffuse = GLVector(0.4f, 0.0f, 0.02f)
        sinusoidal.material.specular = GLVector(0.05f, 0f, 0f)
        bile.addChild(sinusoidal)
        bileScene.addChild(bile)
        scene.addChild(bileScene)

        scene.findObserver().position = GLVector(-6.3036857f, 0.0f, 18.837109f);

        publishedNodes.add(cam)
        publishedNodes.add(drosophilaVolume)
        publishedNodes.add(drosophilaScene)

        publishedNodes.add(histoneVolume)
        publishedNodes.add(histoneScene)

        publishedNodes.add(retinaVolume)
        publishedNodes.add(retinaScene)

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

        val min_delay = 200

        if(publisher != null) {
            thread {
                while (!scene.initialized) {
                    Thread.sleep(1000)
                }

                while (true) {
                    var sleepDuration = 50L


                    arrayOf(drosophilaScene, histoneScene, retinaScene).forEach {
                        if(it.visible) {
                            logger.info("Reading next volume for ${it.name} ...")
                            val start = System.currentTimeMillis()

                            if(it.children[0] is DirectVolumeFullscreen && volumes.containsKey(it.name)) {
                                (it.children[0] as DirectVolumeFullscreen).nextVolume(volumes[it.name]!!)

                                val time_to_read  = System.currentTimeMillis()-start

                                if(it.name == "drosophila") {
                                    sleepDuration = Math.max(40,min_delay-time_to_read)

                                    with(it.children[0] as DirectVolumeFullscreen) {
                                        trangemin = 0.00f
                                        trangemax = .006f
                                        //trangemax = .0003f
                                        alpha_blending = 0.05f
                                        scale = GLVector(1.0f, 1.0f, 1.0f)
                                        voxelSizeX = 1.0f
                                        voxelSizeY = 5.0f
                                        voxelSizeZ = 1.0f
                                    }
                                }

                                if(it.name == "histone") {
                                    sleepDuration = Math.max(300,min_delay-time_to_read)

                                    with(it.children[0] as DirectVolumeFullscreen) {
                                        trangemin = 0.005f
                                        trangemax = 0.04f
                                        alpha_blending = 0.02f
                                        scale = GLVector(1.0f, 1.0f, 1.0f)
                                        voxelSizeX = 1.0f
                                        voxelSizeY = 1.0f
                                        voxelSizeZ = 1.0f
                                    }
                                }

                                if(it.name == "retina") {
                                    sleepDuration = Math.max(20300,min_delay-time_to_read)

                                    with(it.children[0] as DirectVolumeFullscreen) {
                                        trangemin = 0.005f
                                        trangemax = 1.0f
                                        alpha_blending = 0.02f
                                        scale = GLVector(1.0f, 1.0f, 1.0f)
                                        voxelSizeX = 1.0f
                                        voxelSizeY = 1.0f
                                        voxelSizeZ = 5.0f
                                    }
                                }
                            }



                        }
                    }

//                    logger.info("Sleeping for $sleepDuration")
                    // sleep if no volume is active
                    Thread.sleep(sleepDuration)
                }
            }

        }

        thread {
            logger.info("Preloading volumes")
            volumes["histone"]?.map { histoneVolume.preload(Paths.get(it)) }
            volumes["drosophila"]?.map { drosophilaVolume.preload(Paths.get(it)) }
            volumes["retina"]?.map { retinaVolume.preload(Paths.get(it)) }
        }

//        thread {
//            while(true) {
//                Thread.sleep(30000)
//
//                goto_scene_histone.click(0, 0)
//
//                Thread.sleep(30000)
//
//                goto_scene_drosophila.click(0, 0)
//
//                Thread.sleep(30000)
//
//                goto_scene_bile.click(0, 0)
//            }
//        }
    }

    fun getVolumes(path: String): List<String> {
        val folder = File(path)
        val files = folder.listFiles()
        val volumes = files.filter { it.isFile && it.name.endsWith("raw") }.map { it.absolutePath }.sorted()

        volumes.forEach { logger.info("Volume: $it")}

        return volumes
    }

    fun DirectVolumeFullscreen.nextVolume(volumes: List<String>): String {
        var curr = if (volumes.indexOf(this.currentVolume) == -1) {
            0
        } else {
            volumes.indexOf(this.currentVolume)
        }

        if(curr+1 == volumes.size) {
            curr = 0
        }

        val v = volumes[curr+1 % volumes.size]

        this.currentVolume = v

        return v
    }

    fun Node.showAll() {
        this.children.map { visible = true }
        this.visible = true
    }

    fun Node.hideAll() {
        this.children.map { visible = false }
        this.visible = false
    }

    override fun inputSetup() {
        val target = GLVector(1.5f, 5.5f, 55.5f)
        val inputHandler = (hub.get(SceneryElement.Input) as InputHandler)
        val targetArcball = ArcballCameraControl("mouse_control", scene.findObserver(), renderer!!.window.width, renderer!!.window.height, target)
        val fpsControl = FPSCameraControl("mouse_control", scene.findObserver(), renderer!!.window.width, renderer!!.window.height)

        val toggleControlMode = object : ClickBehaviour {
            var currentMode = "fps"

            override fun click(x: Int, y: Int) {
                if (currentMode.startsWith("fps")) {
                    targetArcball.target = GLVector(0.0f, 0.0f, 0.0f)

                    inputHandler.addBehaviour("mouse_control", targetArcball)
                    inputHandler.addBehaviour("scroll_arcball", targetArcball)
                    inputHandler.addKeyBinding("scroll_arcball", "scroll")

                    currentMode = "arcball"
                } else {
                    inputHandler.addBehaviour("mouse_control", fpsControl)
                    inputHandler.removeBehaviour("scroll_arcball")

                    currentMode = "fps"
                }

                System.out.println("Switched to $currentMode control")
            }
        }


        goto_scene_bile = ClickBehaviour { _, _ ->
            bileScene.showAll()
            histoneScene.hideAll()
            drosophilaScene.hideAll()
            retinaScene.hideAll()

            scene.findObserver().position = GLVector(-6.3036857f, 0.0f, 18.837109f)
        }

        goto_scene_histone = ClickBehaviour { _, _ ->
            bileScene.hideAll()
            histoneScene.showAll()
            drosophilaScene.hideAll()
            retinaScene.hideAll()

            with(histoneScene.children[0] as DirectVolumeFullscreen) {
                trangemin = 0.005f
                trangemax = 0.04f
                alpha_blending = 0.02f
                scale = GLVector(1.0f, 1.0f, 1.0f)
                voxelSizeX = 1.0f
                voxelSizeY = 1.0f
                voxelSizeZ = 1.0f
            }


            scene.findObserver().position = GLVector(-0.16273244f, -0.85279214f, 1.0995241f)
        }

        goto_scene_drosophila = ClickBehaviour { _, _ ->
            bileScene.hideAll()
            histoneScene.hideAll()
            drosophilaScene.showAll()
            retinaScene.hideAll()

            with(drosophilaScene.children[0] as DirectVolumeFullscreen) {
                trangemin = 0.00f
                //trangemax = .006f
                trangemax = .0003f
                alpha_blending = 0.05f
                scale = GLVector(1.0f, 1.0f, 1.0f)
                voxelSizeX = 1.0f
                voxelSizeY = 5.0f
                voxelSizeZ = 1.0f
            }

            scene.findObserver().position = GLVector(0.0f, -1.3190879f, 0.48231834f)

        }

        goto_scene_retina = ClickBehaviour { _, _ ->
            bileScene.hideAll()
            histoneScene.hideAll()
            drosophilaScene.hideAll()
            retinaScene.showAll()

            with(retinaScene.children[0] as DirectVolumeFullscreen) {
                trangemin = 0.00f
                //trangemax = .006f
                trangemax = .5f
                alpha_blending = 0.01f
                scale = GLVector(1.0f, 1.0f, 1.0f)
                voxelSizeX = 1.0f
                voxelSizeY = 1.0f
                voxelSizeZ = 5.0f
            }

            //scene.findObserver().position = GLVector(-0.16273244f, -0.85279214f, 1.0995241f)
            scene.findObserver().position = GLVector(0.0f,-1.1f, 2.0f);
        }

        inputHandler.addBehaviour("toggle_control_mode", toggleControlMode)
        inputHandler.addKeyBinding("toggle_control_mode", "C")

        inputHandler.addBehaviour("goto_scene_bile", goto_scene_bile)
        inputHandler.addBehaviour("goto_scene_histone", goto_scene_histone)
        inputHandler.addBehaviour("goto_scene_drosophila", goto_scene_drosophila)
        inputHandler.addBehaviour("goto_scene_retina", goto_scene_retina)

        inputHandler.addKeyBinding("goto_scene_bile", "shift 1")
        inputHandler.addKeyBinding("goto_scene_histone", "shift 2")
        inputHandler.addKeyBinding("goto_scene_drosophila", "shift 3")
        inputHandler.addKeyBinding("goto_scene_retina", "shift 4")
    }

    @Test override fun main() {
        super.main()
    }
}
