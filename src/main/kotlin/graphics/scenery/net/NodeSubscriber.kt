package graphics.scenery.net

import org.joml.Matrix4f
import org.joml.Vector3f
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.*
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.objenesis.strategy.StdInstantiatorStrategy
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.io.ByteArrayInputStream
import java.io.StreamCorruptedException
import java.util.concurrent.ConcurrentHashMap


/**
 * Created by ulrik on 4/4/2017.
 */
class NodeSubscriber(override var hub: Hub?, val address: String = "udp://localhost:6666", val context: ZContext = ZContext(4)) : Hubable {

    private val logger by LazyLogger()
    var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap()
    var subscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
    val kryo = Kryo()

    init {
        subscriber.connect(address)
        subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)
        kryo.isRegistrationRequired = false

        kryo.register(Matrix4f::class.java)
        kryo.register(Vector3f::class.java)
        kryo.register(Node::class.java)
        kryo.register(Camera::class.java)
        kryo.register(DetachedHeadCamera::class.java)
        kryo.register(Quaternion::class.java)
        kryo.register(Mesh::class.java)
        kryo.register(Volume::class.java)
        kryo.register(OrientedBoundingBox::class.java)
        kryo.register(TransferFunction::class.java)
        kryo.register(PointLight::class.java)
        kryo.register(Light::class.java)
        kryo.register(Sphere::class.java)
        kryo.register(Box::class.java)
        kryo.register(Icosphere::class.java)
        kryo.register(Cylinder::class.java)
        kryo.register(Arrow::class.java)
        kryo.register(Line::class.java)
        kryo.register(FloatArray::class.java)
        kryo.register(GeometryType::class.java)

        kryo.instantiatorStrategy = Kryo.DefaultInstantiatorStrategy(StdInstantiatorStrategy())
    }

    fun process() {
        while (true) {
            var start: Long
            var duration: Long = 0L
            try {
                start = System.nanoTime()
                val id = subscriber.recvStr().toInt()

                logger.trace("Received {} for deserializiation", id)
                duration = (System.nanoTime() - start)
                val payload = subscriber.recv()

                if (payload != null) {
                    logger.trace("payload is not null, node id={}, have={}", id, nodes.containsKey(id))
                    nodes[id]?.let { node ->
                        val bin = ByteArrayInputStream(payload)
                        val input = Input(bin)
                        val o = kryo.readClassAndObject(input) as? Node ?: return@let
                        val oSpatial = o.spatial()
                        if (oSpatial != null) {
                            node.spatial {
                                position = oSpatial.position
                                rotation = oSpatial.rotation
                                scale = oSpatial.scale
                            }
                        }
                        node.visible = o.visible

                        if (o is Volume && node is Volume && node.renderable()?.initialized ?: false) {
                            TODO("Reimplement changes for synchronising volumes")
                        }

                        if(o is PointLight && node is PointLight) {
                            node.emissionColor = o.emissionColor
                            node.lightRadius = o.lightRadius
                        }

                        if(o is BoundingGrid && node is BoundingGrid) {
                            node.gridColor = o.gridColor
                            node.lineWidth = o.lineWidth
                            node.numLines = o.numLines
                            node.ticksOnly = o.ticksOnly
                        }

                        input.close()
                        bin.close()
                    }
                }
            } catch(e: StreamCorruptedException) {
                logger.warn("Corrupted stream")
            } catch(e: NullPointerException) {
                logger.warn("NPE while receiving payload: $e")
            }

            hub?.get<Statistics>(SceneryElement.Statistics)?.add("Deserialise", duration.toFloat())

        }
    }

    fun close() {
        context.destroySocket(subscriber)
        context.close()
    }
}
