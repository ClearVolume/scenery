package graphics.scenery.tests.examples.basic;

import graphics.scenery.*;
import graphics.scenery.backends.Renderer;
import graphics.scenery.attribute.material.Material;
import graphics.scenery.textures.Texture;
import graphics.scenery.utils.Image;
import org.joml.Vector3f;

/**
 * Created by kharrington on 7/6/16.
 */
class TexturedCubeJavaExample extends SceneryBase {
    public static void main(String[] args) {
       final SceneryBase app = new TexturedCubeJavaExample();
       app.main();
    }

    public TexturedCubeJavaExample() {
        super("TexturedCubeJavaExample", 512, 512, true);
    }

    public void init() {

        setRenderer( Renderer.createRenderer(getHub(), getApplicationName(), getScene(), getWindowWidth(), getWindowHeight()));
        getHub().add(SceneryElement.Renderer, getRenderer());

        final Box box = new Box(new Vector3f(1.0f, 1.0f, 1.0f), false);
        Material material = box.material();
        material.setAmbient( new Vector3f(1.0f, 0.0f, 0.0f) );
        material.setDiffuse( new Vector3f(0.0f, 1.0f, 0.0f) );
        material.setSpecular( new Vector3f(1.0f, 1.0f, 1.0f) );
        material.getTextures().put("diffuse", Texture.fromImage(Image.fromResource("textures/helix.png", this.getClass())));
        box.spatial().setPosition( new Vector3f(0.0f, 0.0f, 0.0f) );

        getScene().addChild(box);

        PointLight light = new PointLight(15.0f);
        light.spatial().setPosition(new Vector3f(0.0f, 0.0f, 2.0f));
        light.setIntensity(5.0f);
        light.setEmissionColor(new Vector3f(1.0f, 1.0f, 1.0f));
        getScene().addChild(light);

        Camera cam = new DetachedHeadCamera();
        cam.spatial().setPosition( new Vector3f(0.0f, 0.0f, 5.0f) );
        cam.perspectiveCamera(50.0f, getRenderer().getWindow().getWidth(), getRenderer().getWindow().getHeight(), 0.1f, 1000.0f);
        getScene().addChild(cam);

        Thread rotator = new Thread(() -> {
            while (true) {
                box.spatial().getRotation().rotateY(0.01f);
                box.spatial().setNeedsUpdate(true);

                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        rotator.start();
    }
}

