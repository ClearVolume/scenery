package graphics.scenery.tests.examples.cluster

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.Mesh
import graphics.scenery.mesh.MeshImporter
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.utils.extensions.times
import org.junit.Test

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class BileExample: SceneryBase("Bile Canaliculi example") {
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
            position = Vector3f(.0f, -0.4f, 5.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val shell = Box(Vector3f(120.0f, 120.0f, 120.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.Front
        shell.material.diffuse = Vector3f(0.0f, 0.0f, 0.0f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        scene.addChild(shell)

        val lights = (0..4).map {
            PointLight(radius = 200.0f)
        }

        val tetrahedron = listOf(
            Vector3f(1.0f, 0f, -1.0f/Math.sqrt(2.0).toFloat()),
            Vector3f(-1.0f,0f,-1.0f/Math.sqrt(2.0).toFloat()),
            Vector3f(0.0f,1.0f,1.0f/Math.sqrt(2.0).toFloat()),
            Vector3f(0.0f,-1.0f,1.0f/Math.sqrt(2.0).toFloat()))

        tetrahedron.mapIndexed { i, position ->
            lights[i].position = position * 50.0f
            lights[i].emissionColor = Vector3f(1.0f, 0.5f,0.3f)//Random.random3DVectorFromRange(0.2f, 0.8f)
            lights[i].intensity = 200.2f
            scene.addChild(lights[i])
        }

        val bile = MeshImporter.readFrom("M:/meshes/adult_mouse_bile_canaliculi_network_2.stl").apply {
            scale = Vector3f(0.1f, 0.1f, 0.1f)
            position = Vector3f(-600.0f, -800.0f, -20.0f)
            material.diffuse = Vector3f(0.8f, 0.5f, 0.5f)
            material.specular = Vector3f(1.0f, 1.0f, 1.0f)
            material.roughness = 0.5f
        }
        scene.addChild(bile)


        publishedNodes.add(cam)
        publishedNodes.add(bile)
        publishedNodes.add(shell)

        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

        publishedNodes.forEachIndexed { index, node ->
            publisher?.nodes?.put(13337 + index, node)

            subscriber?.nodes?.put(13337 + index, node)
        }
    }

    @Test override fun main() {
        super.main()
    }
}
