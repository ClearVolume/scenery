package graphics.scenery.backends

import com.jogamp.opengl.GLAutoDrawable
import graphics.scenery.utils.SceneryFXPanel
import graphics.scenery.utils.SceneryJPanel
import javafx.application.Platform
import javafx.stage.Stage
import org.lwjgl.glfw.GLFW.*
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Abstraction class for GLFW, ClearGL and JavaFX windows
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
sealed class SceneryWindow {
    /** The default window state, before it becomes initialized to a specific window kind */
    class UninitializedWindow : SceneryWindow()

    /** GLFW window, with [window] being the pointer to GLFW's window object. */
    class GLFWWindow(var window: Long) : SceneryWindow()

    /** ClearGL (JOGL) window, with [window] being the reference to a [cleargl.ClearGLWindow]. */
    class ClearGLWindow(var window: cleargl.ClearGLWindow) : SceneryWindow()

    /** JOGL GLAutoDrawable, with [drawable] being the reference to a [GLAutoDrawable]. */
    class JOGLDrawable(var drawable: GLAutoDrawable) : SceneryWindow()

    /** JavaFX window or stage, with [panel] being the [SceneryFXPanel] scenery will render to. */
    class JavaFXStage(var panel: SceneryFXPanel) : SceneryWindow()

    /** Swing window with [panel] being the [SceneryJPanel] */
    class SwingWindow(var panel: SceneryJPanel) : SceneryWindow()

    /** Headless window with no chrome whatsoever. */
    class HeadlessWindow : SceneryWindow()

    /** Whether the window should be closed on the next main loop iteration. */
    var shouldClose = false

    /** Window width, can only be set from package-internal functions. */
    var width = 0
        internal set
    /** Window height, can only be set from package-internal functions. */
    var height = 0
        internal set
    /** Window fullscreen state, can only be set from package-internal functions. */
    var isFullscreen = false
        internal set

    /**
     * Sets the title of this window to [title].
     */
    fun setTitle(title: String) {
        when (this) {
            is UninitializedWindow -> {
            }
            is GLFWWindow -> glfwSetWindowTitle(window, title)
            is ClearGLWindow -> window.windowTitle = title
            is JavaFXStage -> {
                Platform.runLater { (panel.scene.window as? Stage)?.title = title }
            }
            is SwingWindow -> {
                val window = SwingUtilities.getWindowAncestor(panel)
                if (window != null) {
                    (window as? JFrame)?.title = title
                }
            }
            is HeadlessWindow -> {
            }
        }
    }

    /**
     * Poll events function, in case the window system requires event polling.
     * (Only the case for GLFW so far)
     */
    fun pollEvents() {
        if (this is GLFWWindow) {
            if (glfwWindowShouldClose(window)) {
                shouldClose = true
            }

            glfwPollEvents()
        }
    }
}
