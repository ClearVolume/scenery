package graphics.scenery.tests.examples.basic;

import graphics.scenery.*;
import graphics.scenery.backends.Renderer;
import graphics.scenery.mesh.Box;
import graphics.scenery.textures.Texture;
import graphics.scenery.utils.Image;
import org.joml.Vector3f;
import org.junit.Test;

/**
 * Created by kharrington on 7/6/16.
 */
public class TexturedCubeJavaExample {
    @Test
    public void testExample() throws Exception {
        TexturedCubeJavaApplication viewer = new TexturedCubeJavaApplication( "scenery - TexturedCubeExample", 512, 512);
        viewer.main();
    }

    private class TexturedCubeJavaApplication extends SceneryBase {
        public TexturedCubeJavaApplication(String applicationName, int windowWidth, int windowHeight) {
            super(applicationName, windowWidth, windowHeight, true);
        }

        public void init() {

            setRenderer( Renderer.createRenderer(getHub(), getApplicationName(), getScene(), getWindowWidth(), getWindowHeight()));
            getHub().add(SceneryElement.Renderer, getRenderer());

            Material boxmaterial = new Material();
            boxmaterial.setAmbient( new Vector3f(1.0f, 0.0f, 0.0f) );
            boxmaterial.setDiffuse( new Vector3f(0.0f, 1.0f, 0.0f) );
            boxmaterial.setSpecular( new Vector3f(1.0f, 1.0f, 1.0f) );
            boxmaterial.getTextures().put("diffuse", Texture.fromImage(Image.fromResource("textures/helix.png", this.getClass())));

            final Box box = new Box(new Vector3f(1.0f, 1.0f, 1.0f), false);
            box.setMaterial( boxmaterial );
            box.setPosition( new Vector3f(0.0f, 0.0f, 0.0f) );

            getScene().addChild(box);

            PointLight light = new PointLight(15.0f);
            light.setPosition(new Vector3f(0.0f, 0.0f, 2.0f));
            light.setIntensity(100.0f);
            light.setEmissionColor(new Vector3f(1.0f, 1.0f, 1.0f));
            getScene().addChild(light);

            Camera cam = new DetachedHeadCamera();
            cam.setPosition( new Vector3f(0.0f, 0.0f, 5.0f) );
            cam.perspectiveCamera(50.0f, getRenderer().getWindow().getWidth(), getRenderer().getWindow().getHeight(), 0.1f, 1000.0f);
            getScene().addChild(cam);

            Thread rotator = new Thread(() -> {
                while (true) {
                    box.getRotation().rotateY(0.01f);
                    box.setNeedsUpdate(true);

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

}
