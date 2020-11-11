package graphics.scenery.net

import org.joml.Matrix4f
import org.joml.Vector3f
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Output
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.*
import graphics.scenery.mesh.*
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMQException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by ulrik on 4/4/2017.
 */

class NodePublisher(override var hub: Hub?, val address: String = "tcp://127.0.0.1:6666", val context: ZContext = ZContext(4)): Hubable {
    private val logger by LazyLogger()

    var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap()
    var publisher: ZMQ.Socket = context.createSocket(ZMQ.PUB)
    val kryo = Kryo()
    var port: Int

    init {
        port = try {
            publisher.bind(address)
            address.substringAfterLast(":").toInt()
        } catch (e: ZMQException) {
            logger.warn("Binding failed, trying random port: $e")
            publisher.bindToRandomPort(address.substringBeforeLast(":"))
        }
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
    }

    fun publish() {
        nodes.forEach { guid, node ->
            val start = System.nanoTime()
            try {
                val bos = ByteArrayOutputStream()
                val output = Output(bos)
                kryo.writeClassAndObject(output, node)
                output.flush()

                publisher.sendMore(guid.toString())
                publisher.send(bos.toByteArray())
                Thread.sleep(1)
//                logger.info("Sending ${node.name} with length ${payload.size}")

                output.close()
                bos.close()
            } catch(e: IOException) {
                logger.warn("in ${node.name}: ${e}")
            } catch(e: AssertionError) {
                logger.warn("assertion: ${node.name}: ${e}")
            }

            val duration = (System.nanoTime() - start).toFloat()
            (hub?.get(SceneryElement.Statistics) as Statistics).add("Serialise", duration)
        }

    }

    fun close() {
        context.destroySocket(publisher)
        context.close()
    }
}
