package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.geometry.GeometryType
import org.joml.Quaternionf

class AttributesExample : SceneryBase("AttributesExample") {

    override fun init() {

        val mesh = Mesh()

        // access default attributes (spatial, renderable, material, geometry) of a node that has these attributes

        with(mesh) {
            spatial {
                position = Vector3f(0.1f, 0.2f, 0.3f)
                scale = Vector3f(2f, 2f, 3f)
                rotation = Quaternionf(0f, 0.1f, 0.1f, 1f)
            }
            renderable {
                isBillboard = true
            }
            material {
                ambient = Vector3f(0f, 0.1f, 0.1f)
                diffuse = Vector3f(0f, 0.1f, 0.1f)
                specular = Vector3f(0f, 0.1f, 0.1f)
                metallic = 0.5f
            }
            geometry {
                geometryType = GeometryType.POINTS
            }
        }

        // access attributes of a node of unknown type / with an unknown set of attributes

        val anyNode = mesh as Node

        anyNode.ifSpatial {
            position = Vector3f(3f, 4f, 5f)
        }

        // assign custom attributes
        anyNode.addAttribute(SuperPowers::class.java, DefaultSuperPowers()) {
            canTimeTravel = true
        }

        // create object of custom node class with custom attributes attached by default
        val myNode = SuperGirlNode()

        // access custom attributes..

        myNode.superPowers {
            canFly = true
        }

        // .. when you are not sure whether the node has these attributes:
        mesh.ifHasAttribute(SuperPowers::class.java) {
            canFly = true
        }

        val flashNode = FlashNode()

        flashNode.superPowers {
            speed = 200
        }

        this.close()

    }

    interface SuperPowers {
        var canFly: Boolean
        var canTimeTravel: Boolean
    }

    class DefaultSuperPowers: SuperPowers {
        override var canFly: Boolean = false
        override var canTimeTravel: Boolean = false
    }

    class FlashPowers: SuperPowers {
        override var canFly: Boolean = true
        override var canTimeTravel: Boolean = true
        var speed: Int = 10
    }

    class SuperGirlNode: DefaultNode(), HasSuperPowers {
        init {
            addSuperPowers {
                canFly = true
            }
        }
    }

    class FlashNode: DefaultNode(), HasCustomSuperPowers<FlashPowers> {
        override fun createSuperPowers(): FlashPowers {
            return FlashPowers()
        }
        init {
            addSuperPowers()
        }
    }

    interface HasSuperPowers: HasCustomSuperPowers<SuperPowers> {
        override fun createSuperPowers(): SuperPowers {
            return DefaultSuperPowers()
        }
    }

    interface HasCustomSuperPowers<T: SuperPowers>: Node {

        fun createSuperPowers(): T

        fun addSuperPowers() {
            addAttribute(SuperPowers::class.java, createSuperPowers())
        }

        fun addSuperPowers(block: T.() -> Unit) {
            addAttribute(SuperPowers::class.java, createSuperPowers())
        }

        fun superPowers(block: T.() -> Unit): T {
            return getAttribute(SuperPowers::class.java, block)
        }
        fun superPowers(): T {
            return superPowers {}
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            AttributesExample().main()
        }
    }
}
