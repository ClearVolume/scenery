package graphics.scenery.ui

import glm_.vec2.Vec2
import graphics.scenery.Node
import graphics.scenery.Scene
import imgui.dsl
import imgui.ImGui
import java.text.SimpleDateFormat

object menu {

    operator fun invoke(scene: Scene) {

        ImGui.setNextWindowSize(Vec2(900, 600))

        dsl.window("Tree") {
            scene.print()
        }
    }

    fun Node.print() {

        dsl.treeNode("$name, ($nodeType)") {

            boundingBox?.let {
                ImGui.text("bounding box: $it")
            }
            val date = SimpleDateFormat("dd.MM''yy HH:mm")
            ImGui.text("created: ${date.format(createdAt)}, modified: ${date.format(modifiedAt)}")
            ImGui.text("discoveryBarrier: $discoveryBarrier")
//            ImGui.text("metadata")
//            for ((k, v) in metadata)
//                ImGui.text("\t[$k] = $v")
            ImGui.text("needsUpdate: $needsUpdate, -world: $needsUpdateWorld")
            ImGui.text("uuid: $uuid, wantsComposeModel: $wantsComposeModel")
            for (child in children)
                child.print()
        }
    }
}