package graphics.scenery.tests.examples.advanced

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.Mesh
import graphics.scenery.attribute.material.Material
import graphics.scenery.utils.extensions.minus
import org.joml.Vector3f
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class BloodCellsExample : SceneryBase("BloodCellsExample", windowWidth = 1280, windowHeight = 720) {
    val leucocyteCount = 1000
    val lightCount = 20
    val positionRange = 250.0f

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, 20.0f, -20.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight, 2.0f, 5000.0f)

        scene.addChild(cam)

        val hull = Box(Vector3f(2*positionRange, 2*positionRange, 2*positionRange), insideNormals = true)
        hull.material {
            ambient = Vector3f(0.0f, 0.0f, 0.0f)
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            specular = Vector3f(0.0f, 0.0f, 0.0f)
            cullingMode = Material.CullingMode.Front
        }
        hull.name = "hull"

        scene.addChild(hull)

        val lights = (0 until lightCount).map { PointLight(radius = positionRange) }

        lights.map {
            it.spatial {
                position = Random.random3DVectorFromRange(-positionRange/2, positionRange/2)
            }
            it.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            it.intensity = 0.5f

            scene.addChild(it)
        }

        val erythrocyte = Mesh()
        erythrocyte.readFromOBJ(Mesh::class.java.getResource("models/erythrocyte.obj").file)
        erythrocyte.setMaterial(ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")) {
            ambient = Vector3f(0.1f, 0.0f, 0.0f)
            diffuse = Vector3f(0.9f, 0.0f, 0.02f)
            specular = Vector3f(0.05f, 0f, 0f)
            metallic = 0.01f
            roughness = 0.9f
        }
        erythrocyte.name = "Erythrocyte_Master"
        val erythrocyteInstanced = InstancedNode(erythrocyte)
        scene.addChild(erythrocyteInstanced)

        val leucocyte = Mesh()
        leucocyte.readFromOBJ(Mesh::class.java.getResource("models/leukocyte.obj").file)
        leucocyte.name = "leucocyte_Master"
        leucocyte.setMaterial(ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")) {
            ambient = Vector3f(0.1f, 0.0f, 0.0f)
            diffuse = Vector3f(0.8f, 0.7f, 0.7f)
            specular = Vector3f(0.05f, 0f, 0f)
            metallic = 0.01f
            roughness = 0.5f
        }
        val leucocyteInstanced = InstancedNode(leucocyte)
        scene.addChild(leucocyteInstanced)

        val container = RichNode("Cell container")

        val leucocytes = (0 until leucocyteCount).map {
            val v = leucocyteInstanced.addInstance()
            v.name = "leucocyte_$it"
            v.metadata["axis"] = Vector3f(sin(0.1 * it).toFloat(), -cos(0.1 * it).toFloat(), sin(1.0f*it)*cos(1.0f*it)).normalize()
            v.parent = container

            val scale = Random.randomFromRange(3.0f, 4.0f)
            v.spatial {
                this.scale = Vector3f(scale, scale, scale)
                this.position = Random.random3DVectorFromRange(-positionRange, positionRange)
                this.rotation.rotateXYZ(
                    Random.randomFromRange(0.01f, 0.9f),
                    Random.randomFromRange(0.01f, 0.9f),
                    Random.randomFromRange(0.01f, 0.9f)
                )
            }

            v
        }

        // erythrocytes make up about 40% of human blood, while leucocytes make up about 1%
        val erythrocytes = (0 until leucocyteCount*40).map {
            val v = erythrocyteInstanced.addInstance()
            v.name = "erythrocyte_$it"
            v.metadata["axis"] = Vector3f(sin(0.1 * it).toFloat(), -cos(0.1 * it).toFloat(), sin(1.0f*it)*cos(1.0f*it)).normalize()
            v.parent = container

            val randomScale = Random.randomFromRange(0.5f, 1.2f)
            v.spatial {
                this.scale = Vector3f(randomScale, randomScale, randomScale)
                position = Random.random3DVectorFromRange(-positionRange, positionRange)
                rotation.rotateXYZ(
                    Random.randomFromRange(0.01f, 0.9f),
                    Random.randomFromRange(0.01f, 0.9f),
                    Random.randomFromRange(0.01f, 0.9f)
                )
            }

            v
        }

        scene.addChild(container)

        fun Node.hoverAndTumble(magnitude: Float) {
            val axis = this.metadata["axis"] as? Vector3f ?: return
            this.ifSpatial {
                rotation.rotateAxis(magnitude, axis.x(), axis.y(), axis.z())
                rotation.rotateY(-1.0f * magnitude)
                needsUpdate = true
            }
        }

        thread {
            while(!sceneInitialized()) {
                Thread.sleep(200)
            }

            while(true) {
                erythrocytes.parallelStream().forEach { erythrocyte -> erythrocyte.hoverAndTumble(Random.randomFromRange(0.001f, 0.01f)) }
                leucocytes.parallelStream().forEach { leucocyte -> leucocyte.hoverAndTumble(0.001f) }

                container.spatial{
                    position = position - Vector3f(0.01f, 0.01f, 0.01f)
                    updateWorld(false)
                }

                Thread.sleep(5)
                ticks++
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            BloodCellsExample().main()
        }
    }
}
