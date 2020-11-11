package graphics.scenery.mesh

import assimp.AiScene
import assimp.Importer
import gnu.trove.map.hash.THashMap
import gnu.trove.set.hash.TLinkedHashSet
import graphics.scenery.*
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.times
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.file.Files
import java.util.ArrayList
import java.util.HashMap

object MeshImporter {

    /**
     * Reads geometry from a file given by [filename]. The extension of [filename] will determine
     * whether the file will be read by [readFromOBJ] or [readFromSTL].
     *
     * Materials will be used, if present and [useMaterial] is true.
     */
    fun readFrom(filename: String, useMaterial: Boolean = true, mesh: Mesh = Mesh()): Mesh {
        return when (val ext = filename.substringAfterLast(".").toLowerCase()) {
            "obj" -> readFromOBJ(filename, useMaterial, mesh)
            "stl" -> readFromSTL(filename, mesh)
            else -> throw UnsupportedOperationException("Unknown file format .$ext for file $filename.")
        }
    }

//    fun readWithAssimp(filename: String): Mesh {
//        val scene = Importer().readFile("test/resources/models/OBJ/box.obj")
//
//    }

    /**
     * Read the [Node]'s geometry from an OBJ file, including materials
     *
     * @param[filename] The filename to read from.
     */
    fun readFromOBJ(filename: String, mesh: Mesh = Mesh()): Mesh {
        return readFromOBJ(filename, importMaterials = true, flipNormals = false, mesh = mesh)
    }

    /**
     * Read the [Node]'s geometry from an OBJ file, and choose whether the OBJ-defined materials shall be imported.
     *
     * @param[filename] The filename to read from.
     * @param[importMaterials] Whether a accompanying MTL file shall be used, defaults to true.
     */
    fun readFromOBJ(filename: String, importMaterials: Boolean, mesh: Mesh = Mesh()): Mesh {
        return readFromOBJ(filename, importMaterials, flipNormals = false, useObjGroups = true, mesh)
    }

    /**
     * Read the [Node]'s geometry from an OBJ file, possible including materials
     *
     * @param[filename] The filename to read from.
     * @param[importMaterials] Whether a accompanying MTL file shall be used, defaults to true.
     * @param[flipNormals] Whether to flip the normals after reading them.
     */
    fun readFromOBJ(filename: String, importMaterials: Boolean = true, flipNormals: Boolean = false,
                    useObjGroups: Boolean = true, mesh: Mesh = Mesh()): Mesh {
        val logger by LazyLogger()

        var name = ""

        var boundingBox: FloatArray

        var vertexCount = 0
        var normalCount = 0
        var uvCount = 0
        var indexCount = 0
        var faceCount = 0

        var materials = HashMap<String, Material>()
        val normalSign = if(flipNormals) { -1.0f } else { 1.0f }

        val groupDelimiter = if(useObjGroups) {
            'g'
        } else {
            'o'
        }

        /**
         * Recalculates normals, assuming CCW winding order.
         *
         * @param[vertexBuffer] The vertex list to use
         * @param[normalBuffer] The buffer to store the normals in
         */
        fun calculateNormals(vertexBuffer: FloatBuffer, normalBuffer: FloatBuffer) {
            var i = 0
            while (i < vertexBuffer.limit() - 1) {
                val v1 = Vector3f(vertexBuffer[i], vertexBuffer[i + 1], vertexBuffer[i + 2])
                i += 3

                val v2 = Vector3f(vertexBuffer[i], vertexBuffer[i + 1], vertexBuffer[i + 2])
                i += 3

                val v3 = Vector3f(vertexBuffer[i], vertexBuffer[i + 1], vertexBuffer[i + 2])
                i += 3

                val a = v2 - v1
                val b = v3 - v1

                val n = a.cross(b).normalize() * normalSign

                n.get(normalBuffer)
                n.get(normalBuffer)
                n.get(normalBuffer)
            }
        }

        val p = SystemHelpers.getPathFromString(filename)
        if (!Files.exists(p)) {
            logger.error("Could not read from $filename, file does not exist.")

            return mesh
        }

        val start = System.nanoTime()
        var lines = Files.lines(p)

        var count = 0

        var targetObject: HasGeometry = mesh

        val triangleIndices = intArrayOf(0, 1, 2)
        val quadIndices = intArrayOf(0, 1, 2, 0, 2, 3)
        val quintIndices = intArrayOf(0, 1, 2, 0, 2, 3, 0, 3, 4)

        logger.info("Importing geometry from OBJ file $filename")
        // OBJ files are read line-by-line, then tokenized after removing trailing
        // and leading whitespace. The first non-whitespace string encountered is
        // evaluated as command, according to the OBJ spec.
        var currentName = name
        val vertexCountMap = HashMap<String, Int>(50)
        val faceCountMap = HashMap<String, Int>(50)

        val preparseStart = System.nanoTime()
        logger.info("Starting preparse")
        lines.forEach {
            line ->
            val tokens = line.trim().trimEnd()
            if (tokens.isNotEmpty()) {
                when (tokens[0]) {
                    'f' -> {
                        faceCount++
                        vertexCount += when (tokens.fastSplit(" ").count() - 1) {
                            3 -> 6
                            4 -> 9
                            else -> 0
                        }
                    }
                    groupDelimiter -> {
                        vertexCountMap.putIfAbsent(currentName, vertexCount)
                        faceCountMap.putIfAbsent(currentName, faceCount)
                        vertexCount = 0
                        faceCount = 0
                        currentName = tokens.substringAfter(" ").trim().trimEnd()
                    }
                }
            }
        }
        val preparseDuration = (System.nanoTime() - preparseStart) / 10e5
        logger.info("Preparse took $preparseDuration ms")

        vertexCountMap[currentName] = vertexCount
        vertexCount = 0

        faceCountMap[currentName] = faceCount
        faceCount = 0

        val vertexBuffers = HashMap<String, Triple<FloatBuffer, FloatBuffer, FloatBuffer>>()
        val indexBuffers = HashMap<String, ArrayList<Int>>()
        val faceBuffers = HashMap<String, TIndexedHashSet<Vertex>>()

        vertexCountMap.forEach { objectName, objectVertexCount ->
            vertexBuffers[objectName] = Triple(
                    MemoryUtil.memAlloc(objectVertexCount * mesh.vertexSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(),
                    MemoryUtil.memAlloc(objectVertexCount * mesh.vertexSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(),
                    MemoryUtil.memAlloc(objectVertexCount * mesh.texcoordSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            )

            indexBuffers[objectName] = ArrayList<Int>(objectVertexCount)
            faceBuffers[objectName] = TIndexedHashSet<Vertex>(((faceCountMap[objectName]
                    ?: throw IllegalStateException("Face count map does not contain $objectName")) * 1.5).toInt())
        }

        val tmpV = ArrayList<Float>(vertexCountMap.values.sum() * mesh.vertexSize)
        val tmpN = ArrayList<Float>(vertexCountMap.values.sum() * mesh.vertexSize)
        val tmpUV = ArrayList<Float>(vertexCountMap.values.sum() * mesh.texcoordSize)

        lines = Files.lines(p)

        val vertex = floatArrayOf(0.0f, 0.0f, 0.0f)
        val vertices = ArrayList<Int>(5)
        val normals = ArrayList<Int>(5)
        val uvs = ArrayList<Int>(5)

        boundingBox = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)

        lines.forEach {
            line ->
            val tokens = line.trim().trimEnd()
            if (tokens.isNotEmpty()) {
                when (tokens[0]) {
                    'm' -> {
                        if (importMaterials) {
                            materials = readFromMTL(filename.replace("\\", "/").substringBeforeLast("/") + "/" + tokens.substringAfter(" ").trim().trimEnd())
                        }
                    }

                    'u' -> {
                        if (targetObject is Node && importMaterials) {
                            materials[tokens.substringAfter(" ").trim().trimEnd()]?.let {
                                (targetObject as? Node)?.material = it
                            }
                        }
                    }

                    // vertices are specified as v x y z
                    // normals as vn x y z
                    // uv coords as vt x y z
                    'v' -> {
                        val elements = tokens.fastSplit(" ", skipEmpty = true)
                        when (tokens[1]) {
                            ' ' -> {
                                tmpV.add(elements[1].toFloat())
                                tmpV.add(elements[2].toFloat())
                                tmpV.add(elements[3].toFloat())
                            }

                            // normal coords are specified as vn x y z
                            'n' -> {
                                tmpN.add(elements[1].toFloat() * normalSign)
                                tmpN.add(elements[2].toFloat() * normalSign)
                                tmpN.add(elements[3].toFloat() * normalSign)
                            }

                            // UV coords maybe vt t1 t2 0.0 or vt t1 t2
                            't' -> {
                                tmpUV.add(elements[1].toFloat())
                                tmpUV.add(elements[2].toFloat())
                            }
                        }
                    }

                    // faces can reference to three or more vertices in these notations:
                    // f v1 v2 ... vn
                    // f v1//vn1 v2//vn2 ... vn//vnn
                    // f v1/vt1/vn1 v2/vt2/vn2 ... vn/vtn/vnn
                    'f' -> {
                        count++
                        vertices.clear()
                        normals.clear()
                        uvs.clear()

                        val elements = tokens.fastSplit(" ", skipEmpty = true)
                        elements.subList(1, elements.size).forEach { elem ->
                            val tri = elem.fastSplit("/")
                            vertices.add(tri[0].toInt())
                            tri.getOrNull(1)?.let { if(it.isNotEmpty()) { uvs.add(it.toInt()) } }
                            tri.getOrNull(2)?.let { if(it.isNotEmpty()) { normals.add(it.toInt()) } }
                        }

                        val range = if (vertices.size == 3) {
                            triangleIndices
                        } else if (vertices.size == 4) {
                            quadIndices
                        } else {
                            quintIndices
                        }

                        val faces: TIndexedHashSet<Vertex> = faceBuffers[name] ?: throw IllegalStateException("Face buffer does not contain $name, broken file?")
                        val ib = indexBuffers[name] ?: throw IllegalStateException("Index buffer does not contain $name, broken file?")
                        val indices = vertices.mapIndexed { i, _ ->
                            val face = Vertex(vertices[i], normals.getOrElse(i) { -1 }, uvs.getOrElse(i) { -1 })
                            val present = !faces.add(face)

                            val index = if (!present) {
                                val base = toBufferIndex(tmpV, vertices[i], 3, 0)
                                vertex[0] = tmpV[base]
                                vertex[1] = tmpV[base + 1]
                                vertex[2] = tmpV[base + 2]

                                val vb = vertexBuffers[name] ?: throw IllegalStateException("Vertex buffer map does not contain $name, broken file?")

                                vb.first.put(vertex[0])
                                vb.first.put(vertex[1])
                                vb.first.put(vertex[2])

                                boundingBox[0] = minOf(boundingBox[0], vertex[0])
                                boundingBox[2] = minOf(boundingBox[2], vertex[1])
                                boundingBox[4] = minOf(boundingBox[4], vertex[2])

                                boundingBox[1] = maxOf(boundingBox[1], vertex[0])
                                boundingBox[3] = maxOf(boundingBox[3], vertex[1])
                                boundingBox[5] = maxOf(boundingBox[5], vertex[2])

                                if (normals.size == vertices.size) {
                                    val baseN = toBufferIndex(tmpN, normals[i], 3, 0)
                                    vb.second.put(tmpN[baseN])
                                    vb.second.put(tmpN[baseN + 1])
                                    vb.second.put(tmpN[baseN + 2])
                                } else {
                                    vb.second.put(0.0f)
                                    vb.second.put(0.0f)
                                    vb.second.put(0.0f)
                                }

                                if (uvs.size == vertices.size) {
                                    val baseUV = toBufferIndex(tmpUV, uvs[i], 2, 0)
                                    vb.third.put(tmpUV[baseUV])
                                    vb.third.put(tmpUV[baseUV + 1])
                                } else {
                                    vb.third.put(0.0f)
                                    vb.third.put(0.0f)
                                }

                                faces.size - 1
                            } else {
                                faces.indexOf(face)
                            }

                            index
                        }

                        range.forEach { ib.add(indices[it]) }
                    }

                    's' -> {
                        // TODO: Implement smooth shading across faces
                    }

                    groupDelimiter -> {
                        val vb = vertexBuffers[name] ?: throw IllegalStateException("Vertex buffer map does not contain $name, broken file?")
                        val ib = indexBuffers[name] ?: throw IllegalStateException("Index buffer map does not contain $name, broken file?")

                        if (vb.second.position() == 0) {
                            calculateNormals(vb.first, vb.second)
                        }

                        targetObject.vertices = vb.first
                        targetObject.normals = vb.second
                        targetObject.texcoords = vb.third
                        targetObject.indices = BufferUtils.allocateIntAndPut(ib.toIntArray())
                        targetObject.geometryType = GeometryType.TRIANGLES

                        targetObject.vertices.flip()
                        targetObject.normals.flip()
                        targetObject.texcoords.flip()

                        vertexCount += targetObject.vertices.limit()
                        normalCount += targetObject.normals.limit()
                        uvCount += targetObject.texcoords.limit()
                        indexCount += targetObject.indices.limit()

                        // add new child mesh
                        if (mesh is PointCloud) {
                            val child = PointCloud()
                            child.name = tokens.substringAfter(" ").trim().trimEnd()
                            name = tokens.substringAfter(" ").trim().trimEnd()
                            if (!importMaterials) {
                                child.material = Material()
                            }

                            (targetObject as? PointCloud)?.boundingBox = OrientedBoundingBox(mesh, boundingBox)
                            boundingBox = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)

                            mesh.addChild(child)
                            targetObject = child
                        } else {
                            val child = Mesh()
                            child.name = tokens.substringAfter(" ").trim().trimEnd()
                            name = tokens.substringAfter(" ").trim().trimEnd()
                            if (!importMaterials) {
                                child.material = Material()
                            }

                            (targetObject as? Mesh)?.boundingBox = OrientedBoundingBox(mesh, boundingBox)
                            boundingBox = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)

                            mesh.addChild(child)
                            targetObject = child
                        }
                    }

                    else -> {
                        if (tokens[0] != '#') {
                            logger.warn("Unknown element: $tokens")
                        }
                    }
                }
            }
        }

        val end = System.nanoTime()

        val vb = vertexBuffers[name] ?: throw IllegalStateException("Vertex buffer map does not contain $name, broken file?")
        val ib = indexBuffers[name] ?: throw IllegalStateException("Index buffer map does not contain $name, broken file?")

        // recalculate normals if model did not supply them
        if (vb.second.position() == 0) {
            logger.warn("Model does not provide surface normals. Recalculating...")
            calculateNormals(vb.first, vb.second)
        }

        targetObject.vertices = vb.first
        targetObject.normals = vb.second
        targetObject.texcoords = vb.third
        targetObject.indices = BufferUtils.allocateIntAndPut(ib.toIntArray())

        targetObject.vertices.flip()
        targetObject.normals.flip()
        targetObject.texcoords.flip()

        vertexCount += targetObject.vertices.limit()
        normalCount += targetObject.normals.limit()
        uvCount += targetObject.texcoords.limit()
        indexCount += targetObject.indices.limit()

        if (mesh is PointCloud) {
            (targetObject as? PointCloud)?.boundingBox = OrientedBoundingBox(mesh, boundingBox)
        } else {
            (targetObject as? Mesh)?.boundingBox = OrientedBoundingBox(mesh, boundingBox)
        }

        logger.info("Read ${vertexCount / mesh.vertexSize}/${normalCount / mesh.vertexSize}/${uvCount / mesh.texcoordSize}/$indexCount v/n/uv/i of model $name in ${(end - start) / 1e6} ms")
        return mesh
    }

    private fun String.fastSplit(delimiter: String, limit: Int = 0, list: ArrayList<String> = ArrayList<String>(this.length / 5), skipEmpty: Boolean = false): List<String> {
        val ch: Char = delimiter[0]
        var off = 0
        val limited = limit > 0
        list.clear()

        var next = indexOf(ch, off)
        while (next != -1) {
            if (!limited || list.size < limit - 1) {
                if (!(skipEmpty && next - off < 1)) {
                    list.add(substring(off, next))
                }
                off = next + 1
            } else {    // last one
                //assert (list.size() == limit - 1);
                if (!(skipEmpty && this.length - off < 1)) {
                    list.add(substring(off, this.length))
                }
                off = this.length
                break
            }

            next = indexOf(ch, off)
        }
        // If no match was found, return this
        if (off == 0)
            return arrayListOf(this)

        // Add remaining segment
        if (!limited || list.size < limit) {
            if (!(skipEmpty && this.length - off < 1)) {
                list.add(substring(off, this.length))
            }
        }

        // Construct result
        var resultSize = list.size
        if (limit == 0) {
            while (resultSize > 0 && list[resultSize - 1].isEmpty()) {
                resultSize--
            }
        }

        return list.subList(0, resultSize)
    }

    private data class Vertex(val vertex: Int, val normal: Int, val uv: Int)

    /**
     * Hash set class extending [TLinkedHashSet], providing an additional index for all entries.
     */
    class TIndexedHashSet<E : Any>(initialCapacity: Int) : TLinkedHashSet<E>(initialCapacity, 0.9f) {
        private val index: THashMap<E, Int> = THashMap(initialCapacity)

        /**
         * Adds [element] to the hash set. Returns true if the set was modified by the operation.
         */
        override fun add(element: E): Boolean {
            index.putIfAbsent(element, size)
            return super.add(element)
        }

        /**
         * Returns the index in the set of the object [obj], or -1 if [obj] is not contained in the set.
         */
        fun indexOf(obj: E): Int {
            index[obj]?.let { return it }

            System.err.println("Index not found!")
            return -1
        }
    }

    private fun toBufferIndex(obj: List<Number>, num: Int, vectorSize: Int, offset: Int): Int {
        val index: Int
        if (num >= 0) {
            index = (num - 1) * vectorSize + offset
        } else {
            index = (obj.size / vectorSize + num) * vectorSize + offset
        }

        return index
    }

    /**
     * Reads an OBJ file's material properties from the corresponding MTL file
     *
     * @param[filename] The filename of the MTL file, stored in the OBJ usually
     * @return A HashMap storing material name and [Material].
     */
    fun readFromMTL(filename: String): HashMap<String, Material> {
        val logger by LazyLogger()

        val materials = HashMap<String, Material>()

        val p = SystemHelpers.getPathFromString(filename)
        if (!Files.exists(p)) {
            logger.error("Could not read from $filename, file does not exist.")

            return materials
        }

        val lines = Files.lines(p)
        var currentMaterial: Material? = Material()

        fun addTexture(material: Material?, slot: String, file: String) {
            if(material == null) {
                logger.warn("No current material, but trying to set texture $slot to $file. Broken material file?")
            } else {
                material.textures[slot] = Texture.fromImage(Image.fromStream(FileInputStream(file), file.substringAfterLast(".")))
            }
        }

        logger.info("Importing materials from MTL file $filename")
        // The MTL file is read line-by-line and tokenized, based on spaces.
        // The first non-whitespace token encountered will be evaluated as command to
        // set up the properties of the [Material], which include e.g. textures and colors.
        lines.forEach {
            line ->
            val lineWithoutComments = line.substringBeforeLast("#").trim().trimEnd()
            val tokens = lineWithoutComments.split(" ").filter(String::isNotEmpty)
            if (tokens.isNotEmpty()) {
                val lineAfterFirstToken = lineWithoutComments.substringAfter(tokens[0])
                when (tokens[0]) {
                    "#" -> {
                    }
                    "newmtl" -> {
                        val m = Material()
                        m.name = tokens[1]

                        materials[tokens[1]] = m
                        currentMaterial = m
                    }
                    "Ka" -> currentMaterial?.ambient = Vector3f(tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat())
                    "Kd" -> currentMaterial?.diffuse = Vector3f(tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat())
                    "Ks" -> currentMaterial?.specular = Vector3f(tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat())
                    "d" -> currentMaterial?.blending?.opacity = tokens[1].toFloat()
                    "Tr" -> currentMaterial?.blending?.opacity = 1.0f - tokens[1].toFloat()
                    "illum" -> {
                    }
                    "map_Ka" -> {
                        val mapfile = filename.substringBeforeLast("/") + "/" + tokens[1].replace('\\', '/')
                        addTexture(currentMaterial, "ambient", mapfile)
                    }
                    "map_Ks" -> {
                        val mapfile = filename.substringBeforeLast("/") + "/" + tokens[1].replace('\\', '/')
                        addTexture(currentMaterial, "specular", mapfile)
                    }
                    "map_Kd" -> {
                        val mapfile = if(lineAfterFirstToken.contains(" -o ") || lineAfterFirstToken.contains(" -s ")) {
                            filename.substringBeforeLast("/") + "/" + lineAfterFirstToken.substringAfterLast(" ").replace('\\', '/')
                        } else {
                            filename.substringBeforeLast("/") + "/" + tokens[1].replace('\\', '/')
                        }

                        addTexture(currentMaterial, "diffuse", mapfile)
                    }
                    "map_d" -> {
                        val mapfile = filename.substringBeforeLast("/") + "/" + tokens[1].replace('\\', '/')
                        addTexture(currentMaterial, "alphamask", mapfile)
                    }
                    "disp" -> {
                        val mapfile = filename.substringBeforeLast("/") + "/" + tokens[1].replace('\\', '/')
                        addTexture(currentMaterial, "displacement", mapfile)
                    }
                    "map_bump", "bump" -> {
                        val mapfile = if(lineAfterFirstToken.contains(" -bm ") || lineAfterFirstToken.contains(" -o ") || lineAfterFirstToken.contains(" -s ")) {
                            filename.substringBeforeLast("/") + "/" + lineAfterFirstToken.substringAfterLast(" ").replace('\\', '/')
                        } else {
                            filename.substringBeforeLast("/") + "/" + tokens[1].replace('\\', '/')
                        }

                        addTexture(currentMaterial, "normal", mapfile)
                    }
                    "Tf" -> {
                    }
                }
            }
        }

        return materials
    }

    /**
     * Read the [Node]'s geometry from an STL file
     *
     * @param[filename] The filename to read from.
     */
    fun readFromSTL(filename: String, mesh: Mesh = Mesh()): Mesh {
        val logger by LazyLogger()

        var name = ""
        val vbuffer = ArrayList<Float>()
        val nbuffer = ArrayList<Float>()

        var boundingBox: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)

        val p = SystemHelpers.getPathFromString(filename)
        if (!Files.exists(p)) {
            throw FileNotFoundException("Could not read from $filename, file does not exist.")
        }

        val start = System.nanoTime()
        val lines = Files.lines(p)

        // This lambda is used in case the STL file is stored in ASCII format
        val readFromAscii = {
            logger.info("Importing geometry from ASCII STL file $filename")
            lines.forEach {
                line ->
                val tokens = line.trim().trimEnd().split(" ").map { it.trim().trimEnd() }.filter { it.isNotEmpty() }
                when (tokens[0]) {
                    "solid" -> name = tokens.drop(1).joinToString(" ")
                    "vertex" -> with(tokens.drop(1)) {
                        val x = get(0).toFloat()
                        val y = get(1).toFloat()
                        val z = get(2).toFloat()

                        if (vbuffer.size == 0) {
                            boundingBox = floatArrayOf(x, x, y, y, z, z)
                        }

                        if (x < boundingBox[0]) boundingBox[0] = x
                        if (y < boundingBox[2]) boundingBox[2] = y
                        if (z < boundingBox[4]) boundingBox[4] = z

                        if (x > boundingBox[1]) boundingBox[1] = x
                        if (y > boundingBox[3]) boundingBox[3] = y
                        if (z > boundingBox[5]) boundingBox[5] = z

                        vbuffer.add(x)
                        vbuffer.add(y)
                        vbuffer.add(z)
                    }
                    "facet" -> tokens.drop(2).forEach { nbuffer.add(it.toFloat()) }
                    "outer" -> {
                        for(i in 1..2) {
                            for(it in (nbuffer.size - 3)..(nbuffer.size - 1)) { nbuffer.add(nbuffer[it]) }
                        }
                    }
                    "end" -> {
                    }
                    "endloop" -> {
                    }
                    "endfacet" -> {
                    }
                    "endsolid" -> {
                    }
                    else -> logger.warn("Unknown element: ${tokens.joinToString(" ")}")
                }
            }
        }

        // This lambda is used in case the STL file is stored binary
        val readFromBinary = {
            logger.info("Importing geometry from binary STL file $filename")

            val fis = Files.newInputStream(p)
            val bis = BufferedInputStream(fis)
            val headerB = ByteArray(80)
            val sizeB = ByteArray(4)
            val buffer = ByteArray(12)
            val size: Int

            bis.read(headerB, 0, 80)
            bis.read(sizeB, 0, 4)

            size = ((sizeB[0].toInt() and 0xFF)
                    or ((sizeB[1].toInt() and 0xFF) shl 8)
                    or ((sizeB[2].toInt() and 0xFF) shl 16)
                    or ((sizeB[3].toInt() and 0xFF) shl 24))

            fun readFloatFromInputStream(stream: BufferedInputStream): Float {
                val floatBuf = ByteArray(4)
                val bBuf: ByteBuffer

                stream.read(floatBuf, 0, 4)
                bBuf = ByteBuffer.wrap(floatBuf)
                bBuf.order(ByteOrder.LITTLE_ENDIAN)

                return bBuf.float
            }

            for (i in 1..size) {
                // surface normal
                val n1 = readFloatFromInputStream(bis)
                val n2 = readFloatFromInputStream(bis)
                val n3 = readFloatFromInputStream(bis)

                // vertices
                for (vertex in 1..3) {
                    val x = readFloatFromInputStream(bis)
                    val y = readFloatFromInputStream(bis)
                    val z = readFloatFromInputStream(bis)

                    if (vbuffer.size == 0) {
                        boundingBox = floatArrayOf(x, x, y, y, z, z)
                    }

                    if (x < boundingBox[0]) boundingBox[0] = x
                    if (y < boundingBox[2]) boundingBox[2] = y
                    if (z < boundingBox[4]) boundingBox[4] = z

                    if (x > boundingBox[1]) boundingBox[1] = x
                    if (y > boundingBox[3]) boundingBox[3] = y
                    if (z > boundingBox[5]) boundingBox[5] = z

                    vbuffer.add(x)
                    vbuffer.add(y)
                    vbuffer.add(z)

                    nbuffer.add(n1)
                    nbuffer.add(n2)
                    nbuffer.add(n3)
                }

                bis.read(buffer, 0, 2)
            }

            fis.close()
        }

        val arr = CharArray(6)
        File(p.toUri()).reader().read(arr, 0, 6)

        // If the STL file starts with the string "solid", is must be a ASCII STL file,
        // otherwise it's assumed to be binary.
        if (arr.joinToString("").startsWith("solid ")) {
            readFromAscii
        } else {
            readFromBinary
        }.invoke()

        // normals are incomplete or missing, recalculate
        if (nbuffer.size != vbuffer.size) {
            logger.warn("Model does not supply surface normals, recalculating.")
            nbuffer.clear()

            var i = 0
            while (i < vbuffer.size) {
                val v1 = Vector3f(vbuffer[i], vbuffer[i + 1], vbuffer[i + 2])
                i += 3

                val v2 = Vector3f(vbuffer[i], vbuffer[i + 1], vbuffer[i + 2])
                i += 3

                val v3 = Vector3f(vbuffer[i], vbuffer[i + 1], vbuffer[i + 2])
                i += 3

                val a = v2 - v1
                val b = v3 - v1

                val n = a.cross(b).normalize()

                for (v in 1..3) {
                    nbuffer.add(n.x())
                    nbuffer.add(n.y())
                    nbuffer.add(n.z())
                }
            }
        }


        val end = System.nanoTime()
        logger.info("Read ${vbuffer.size} vertices/${nbuffer.size} normals of model $name in ${(end - start) / 1e6} ms")

        mesh.vertices = BufferUtils.allocateFloatAndPut(vbuffer.toFloatArray())
        mesh.normals = BufferUtils.allocateFloatAndPut(nbuffer.toFloatArray())
        mesh.texcoords = BufferUtils.allocateFloat(0)
        mesh.indices = BufferUtils.allocateInt(0)

        logger.info("Bounding box of $name is ${boundingBox.joinToString(",")}")
        mesh.boundingBox = OrientedBoundingBox(mesh, boundingBox)

        return mesh
    }
}