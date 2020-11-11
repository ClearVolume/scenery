package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.Light
import graphics.scenery.mesh.MeshImporter
import graphics.scenery.mesh.PointCloud
import graphics.scenery.numerics.Random
import org.junit.Test

/**
 * Simple example to demonstrate the drawing of a 3D point cloud.
 *
 * This example will draw a point cloud while 3 lights
 * circle around the scene.
 *
 * @author Kyle Harrington <kharrington@uidaho.edu>
 */
class PointCloudExample : SceneryBase("PointCloudExample") {
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        renderer?.pushMode = true

        val hull = Box(Vector3f(20.0f, 20.0f, 20.0f), insideNormals = true)
        hull.material.diffuse = Vector3f(0.2f, 0.2f, 0.2f)
        hull.material.cullingMode = Material.CullingMode.Front
        scene.addChild(hull)

        val lights = Light.createLightTetrahedron<PointLight>(spread = 5.0f, radius = 15.0f)

        lights.forEach { light ->
            light.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        val cam: Camera = DetachedHeadCamera()
        cam.position = Vector3f(0.0f, 0.0f, 5.0f)
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)

        scene.addChild(cam)

        val file = TexturedCubeExample::class.java.getResource("models/sphere.obj").file
        val pointCloud = PointCloud()
        MeshImporter.readFromOBJ(file, importMaterials = false, pointCloud)
        with(pointCloud) {
            name = "Sphere Mesh"
            for(i in 0 until pointCloud.texcoords.limit()) {
                pointCloud.texcoords.put(i, Random.randomFromRange(10.0f, 25.0f))
            }

            for(i in 0 until pointCloud.normals.limit()) {
                pointCloud.normals.put(i, Random.randomFromRange(0.2f, 0.8f))
            }
            setupPointCloud()

            scene.addChild(this)
        }

    }

    override fun inputSetup() {
        setupCameraModeSwitching(keybinding = "C")
    }

    @Test override fun main() {
        super.main()
    }
}
