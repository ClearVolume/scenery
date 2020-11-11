package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.Mesh
import graphics.scenery.numerics.Random
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Demo animating multiple boxes with instancing.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class MultiBoxInstancedExample : SceneryBase("MultiBoxInstancedExample") {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(10.0f, 10.0f, 10.0f)
            perspectiveCamera(60.0f, windowWidth, windowHeight, 1.0f, 1000.0f)

            scene.addChild(this)
        }

        val boundaryWidth = 50.0
        val boundaryHeight = 50.0

        val container = Mesh()

        val b = Box(Vector3f(0.7f, 0.7f, 0.7f))
        b.name = "boxmaster"
        b.instancedProperties.put("ModelMatrix", { b.model })
        b.material = ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")
        b.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f)
        b.material.ambient = Vector3f(1.0f, 1.0f, 1.0f)
        b.material.specular = Vector3f(1.0f, 1.0f, 1.0f)
        b.material.metallic = 0.0f
        b.material.roughness = 1.0f

        scene.addChild(b)

        (0 until (boundaryWidth * boundaryHeight * boundaryHeight).toInt()).map {
            val inst = Mesh()
            inst.name = "Box_$it"
            inst.material = b.material

            inst.instancedProperties["ModelMatrix"] = { inst.world }

            val k: Double = it.rem(boundaryWidth)
            val j: Double = (it / boundaryWidth).rem(boundaryHeight)
            val i: Double = it / (boundaryWidth * boundaryHeight)

            inst.position = Vector3f(Math.floor(i).toFloat(), Math.floor(j).toFloat(), Math.floor(k).toFloat())

            b.instances.add(inst)
            inst.parent = container
            inst
        }

        val lights = (0..20).map {
            PointLight(radius = 250.0f)
        }.map {
            it.position = Random.random3DVectorFromRange(-100.0f, 100.0f)
            it.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            it.intensity = Random.randomFromRange(0.1f, 0.5f)
            it
        }

        lights.forEach { scene.addChild(it) }

        val hullbox = Box(Vector3f(100.0f, 100.0f, 100.0f))
        hullbox.position = Vector3f(0.0f, 0.0f, 0.0f)
        hullbox.name = "hullbox"
        hullbox.material.ambient = Vector3f(0.6f, 0.6f, 0.6f)
        hullbox.material.diffuse = Vector3f(0.4f, 0.4f, 0.4f)
        hullbox.material.specular = Vector3f(0.0f, 0.0f, 0.0f)
        hullbox.material.cullingMode = Material.CullingMode.Front

        scene.addChild(hullbox)

        thread {
            while (running) {
                container.rotation.rotateXYZ(0.001f, 0.001f, 0.0f)
                container.needsUpdateWorld = true
                container.needsUpdate = true
                container.updateWorld(true, false)

                val inst = Mesh()
                inst.instancedProperties["ModelMatrix"] = { inst.world }
                inst.position = Random.random3DVectorFromRange(-40.0f, 40.0f)
                inst.parent = container
                b.instances.add(inst)
                b.instances.removeAt(kotlin.random.Random.nextInt(b.instances.size - 1))

                Thread.sleep(20)
            }
        }
    }

    @Test override fun main() {
        super.main()
    }

}
