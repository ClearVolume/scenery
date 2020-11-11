package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.mesh.Box
import graphics.scenery.mesh.PointCloud
import graphics.scenery.numerics.Random
import org.junit.Test
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.widget.FileWidget


/**
 * Example to load localisation microscopy files.
 * Files have to have the format X\tY\tZ\tdX\tdY
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class LocalisationExample : SceneryBase("Localisation Microscopy Rendering example", 1280, 720) {
    var hmd: TrackedStereoGlasses? = null

    override fun init() {
        val files = ArrayList<String>()

        val c = Context()
        val ui = c.getService(UIService::class.java)
        val chosenFile = ui.chooseFiles(null, emptyList(),
            { it.isFile && (it.name.endsWith(".csv") || it.name.endsWith(".tsv") || it.name.endsWith(".xls")) },
            FileWidget.OPEN_STYLE)
        chosenFile.forEach { files.add(it.absolutePath) }

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            position = Vector3f(0.0f, 0.5f, 5.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val shell = Box(Vector3f(20.0f, 20.0f, 20.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.Front
        shell.material.diffuse = Vector3f(0.9f, 0.9f, 0.9f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        scene.addChild(shell)

        if(System.getProperty("datasets") != null) {
            files.clear()
            files.addAll(System.getProperty("datasets").split(";").toList())
        }

        // channel colors for red, green, blue
        val channelColors = arrayOf(
            Vector3f(0.1f, 0.8f, 0.0f),
            Vector3f(1.0f, 0.04f, 0.04f),
            Vector3f(0.1f, 0.1f, 0.8f))

        files.mapIndexed { i, file ->
            val dataset = PointCloud()
            dataset.readFromPALM(file)
            dataset.material.diffuse = channelColors.getOrElse(i, { Random.random3DVectorFromRange(0.1f, 0.9f) })
            dataset.fitInto(5.0f)
            dataset
        }.forEach {
            scene.addChild(it)
        }

        val light = PointLight(radius = 40.0f)
        light.intensity = 500.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)


    }

    override fun inputSetup() {
        setupCameraModeSwitching()
    }

    @Test override fun main() {
        super.main()
    }
}
