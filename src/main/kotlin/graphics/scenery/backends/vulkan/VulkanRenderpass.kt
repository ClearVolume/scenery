package graphics.scenery.backends.vulkan

import org.joml.Vector3f
import graphics.scenery.GeometryType
import graphics.scenery.Node
import graphics.scenery.Settings
import graphics.scenery.backends.*
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.RingBuffer
import org.joml.Vector2f
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.LinkedHashMap

/**
 * Class to encapsulate a Vulkan renderpass with [name] and associated [RenderConfigReader.RenderConfig] [config].
 * The renderpass will be created on [device]. A [pipelineCache] can be used for performance gains. The available vertex descriptors need to be handed
 * over in [vertexDescriptors].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanRenderpass(val name: String, var config: RenderConfigReader.RenderConfig,
                       val device: VulkanDevice,
                       val pipelineCache: Long,
                       val vertexDescriptors: ConcurrentHashMap<VulkanRenderer.VertexDataKinds, VulkanRenderer.VertexDescription>,
                       val ringBufferSize: Int = 2): AutoCloseable {

    protected val logger by LazyLogger()

    /** [VulkanFramebuffer] inputs of this render pass */
    val inputs = ConcurrentHashMap<String, VulkanFramebuffer>()
    /** [VulkanFramebuffer] outputs of this render pass */
    val output = ConcurrentHashMap<String, VulkanFramebuffer>()

    /** The pipelines this renderpass contains */
    var pipelines = ConcurrentHashMap<String, VulkanPipeline>()
        protected set
    /** The UBOs this renderpass contains, e.g. for storage of shader parameters */
    var UBOs = ConcurrentHashMap<String, VulkanUBO>()
        protected set
    /** Descriptor sets needed */
    var descriptorSets = ConcurrentHashMap<String, Long>()
        protected set
    /** Descriptor set layouts needed */
    var descriptorSetLayouts = LinkedHashMap<String, Long>()
        protected set
    protected var oldDescriptorSetLayouts = ArrayList<Pair<String, Long>>()
    protected var ownDescriptorSetLayouts = HashSet<Long>()

    /** Semaphores this renderpass is going to wait on when executed */
    var waitSemaphores = memAllocLong(1)
        protected set
    /** Stages this renderpass will wait for when executed */
    var waitStages = memAllocInt(1)
        protected set
    /** Semaphores this renderpass is going to signal after finishing execution */
    var signalSemaphores = memAllocLong(1)
        protected set
    /** Pointers to command buffers associated with running this renderpass */
    var submitCommandBuffers = memAllocPointer(1)
        protected set

    /**
     * The command buffer associated with this render pass. Command buffers
     * are usually multi-buffered, their backing store is contained in the [RingBuffer]
     * [commandBufferBacking]. When requesting this command buffer, the ring buffer
     * will hop forward one, such that the next request will return the next command buffer
     * in the ring buffer, and so on. Be sure to only request a command buffer once, then
     * store the result and use this.
     * */
    var commandBuffer: VulkanCommandBuffer
        get() {
            return commandBufferBacking.get()
        }

        set(b) {
            commandBufferBacking.put(b)
        }

    private var commandBufferBacking = RingBuffer(size = ringBufferSize,
        default = { VulkanCommandBuffer(device, null, true) })

    /** This renderpasses' semaphore */
    var semaphore: Long = -1L
        get() {
            return semaphoreBacking.get()
        }

    private var semaphoreBacking = RingBuffer(size = ringBufferSize,
        default = { device.createSemaphore() },
        cleanup = { device.removeSemaphore(it) }
    )

    /** This renderpasses' [RenderConfigReader.RenderpassConfig]. */
    var passConfig: RenderConfigReader.RenderpassConfig = config.renderpasses.getValue(name)
        protected set

    /** Whether this renderpass will render to the viewport or to a [VulkanFramebuffer] */
    var isViewportRenderpass = false

    /** The number of command buffers to keep in the [RingBuffer] [commandBufferBacking]. */
    var commandBufferCount = 3
        set(count) {
            // clean up old backing
            (1..commandBufferBacking.size).forEach { commandBufferBacking.get().close() }
            commandBufferBacking.reset()

            field = count

            commandBufferBacking = RingBuffer(size = count,
                default = { VulkanCommandBuffer(device, null, true) })
        }

    /** Timestamp of the renderpass recreation */
    var recreated: Long = 0
        protected set

    private var currentPosition = 0

    /**
     * Vulkan metadata class, keeping information about viewports, scissor areas, etc.
     */
    class VulkanMetadata(var descriptorSets: LongBuffer = memAllocLong(10),
                              var vertexBufferOffsets: LongBuffer = memAllocLong(4),
                              var scissor: VkRect2D.Buffer = VkRect2D.calloc(1),
                              var viewport: VkViewport.Buffer = VkViewport.calloc(1),
                              var vertexBuffers: LongBuffer = memAllocLong(4),
                              var clearValues: VkClearValue.Buffer? = null,
                              var renderArea: VkRect2D = VkRect2D.calloc(),
                              var renderPassBeginInfo: VkRenderPassBeginInfo = VkRenderPassBeginInfo.calloc(),
                              var uboOffsets: IntBuffer = memAllocInt(16),
                              var eye: IntBuffer = memAllocInt(1),
                              var renderLists: HashMap<VulkanCommandBuffer, Array<Node>> = HashMap()): AutoCloseable {

        /** Close this metadata instance, and frees all members */
        override fun close() {
            memFree(descriptorSets)
            memFree(vertexBufferOffsets)
            scissor.free()
            viewport.free()
            memFree(vertexBuffers)
            clearValues?.free()
            renderArea.free()
            renderPassBeginInfo.free()
            memFree(uboOffsets)
            memFree(eye)
        }

    }

    /** [VulkanMetadata] for this renderpass */
    var vulkanMetadata = VulkanMetadata()
        protected set

    init {
        recreated = System.nanoTime()
    }

    /**
     * Initialises descriptor set layouts coming for the passes' [inputs].
     */
    fun initializeInputAttachmentDescriptorSetLayouts(shaderModules: List<VulkanShaderModule>) {
        var input = 0
        logger.debug("Renderpass $name has inputs ${inputs.keys.joinToString(", ")}")
        val relevantFramebuffers = if(passConfig.type == RenderConfigReader.RenderpassType.compute) {
            inputs.entries + output.entries
        } else {
            inputs.entries
        }

        relevantFramebuffers.reversed().forEach { inputFramebuffer ->
            // we need to discern here whether the entire framebuffer is the input, or
            // only a part of it (indicated by a dot in the name)
            val descriptorNum = if(inputFramebuffer.key.contains(".")) {
                1
            } else {
                inputFramebuffer.value.attachments.count()
            }

            val (dsl, ds) = if(inputFramebuffer.key.contains(".")) {
                val targetName = inputFramebuffer.key.substringBefore(".")
                val attachmentName = inputFramebuffer.key.substringAfter(".")
                val attachment = inputFramebuffer.value.attachments[attachmentName] ?: throw IllegalStateException("Framebuffer $inputFramebuffer does not contain attachment $attachmentName")

                config.rendertargets[targetName] ?: throw IllegalStateException("Rendertargets do not contain required target ${inputFramebuffer.key}")

                attachment.descriptorSet

                when(passConfig.type) {
                    RenderConfigReader.RenderpassType.geometry,
                    RenderConfigReader.RenderpassType.quad,
                    RenderConfigReader.RenderpassType.lights -> attachment.descriptorSetLayout to attachment.descriptorSet
                    RenderConfigReader.RenderpassType.compute -> attachment.loadStoreDescriptorSetLayout!! to attachment.loadStoreDescriptorSet!!
                }
            } else {
                if(passConfig.type == RenderConfigReader.RenderpassType.compute) {
                    inputFramebuffer.value.imageLoadStoreDescriptorSetLayout to inputFramebuffer.value.imageLoadStoreDescriptorSet
                } else {
                    inputFramebuffer.value.outputDescriptorSetLayout to inputFramebuffer.value.outputDescriptorSet
                }
            }

            logger.debug("$name: descriptor set for ${inputFramebuffer.key} is ${ds.toHexString()}")

            val searchKeys = when {
                inputFramebuffer.key.startsWith("Viewport") -> {
                    listOf("Viewport")
                }
                inputFramebuffer.key.contains(".") -> {
                    listOf(inputFramebuffer.key.substringAfter("."))
                }
                else -> {
                    config.rendertargets[inputFramebuffer.key]?.attachments?.keys ?: throw IllegalStateException("$name: Rendertargets do not contain required target ${inputFramebuffer.key}")
                }
            }

            logger.debug("$name: Search keys for input attachments: ${searchKeys.joinToString(",")}, descriptorNum=$descriptorNum")

            val nameInShader = passConfig.inputs?.firstOrNull { it.name == inputFramebuffer.key }?.shaderInput ?: if(passConfig.output.name == inputFramebuffer.key.substringBeforeLast("-")) { passConfig.output.shaderInput } else { null }
            logger.debug("$name: Name declared in shader is: $nameInShader, descriptorNum=$descriptorNum")

            val spec = shaderModules
                .flatMap { it.uboSpecs.entries }
                .firstOrNull { entry ->
                    logger.debug("Entry name: ${entry.component1()}, size=${entry.component2().members.count()}")
                    (entry.component2().members.count() == descriptorNum
                        && (entry.component1().startsWith("Inputs") || entry.component1().startsWith("Output"))
                        && searchKeys.map {
                            logger.debug("Looking for Input$it or Output$it")
                            entry.component2().members.containsKey("Input$it")
                                || entry.component2().members.containsKey("Output$it")
                                || entry.component2().members.containsKey(nameInShader)
                        }.all { it }) || (entry.component1() == nameInShader && entry.component2().members.count() == 0)
                }

            if(spec != null) {
                val inputKey = "input-${this.name}-${spec.value.set}"

                logger.debug("$name: Creating input descriptor set for ${inputFramebuffer.key}, $inputKey ($nameInShader)")
                descriptorSetLayouts[inputKey] = dsl/*?.let {
                    oldDSL ->
                    logger.debug("$name: Removing old DSL for $inputKey, $oldDSL.")
                    vkDestroyDescriptorSetLayout(device.vulkanDevice, oldDSL, null)
                }*/
                descriptorSets[inputKey] = ds
                if(nameInShader != null) {
                    descriptorSetLayouts[nameInShader] = dsl
                    descriptorSets[nameInShader] = ds
                }
                input++
            } else {
//                vkDestroyDescriptorSetLayout(device.vulkanDevice, dsl, null)
                logger.debug("$name: Shader does not use input of ${inputFramebuffer.key}. Check if your shader should be doing that.")
            }
        }
    }

    /**
     * Initialiases descriptor set layours associated with this passes' shader parameters.
     */
    fun initializeShaderParameterDescriptorSetLayouts(settings: Settings) {
        if(passConfig.parameters.isEmpty()) {
            return
        }
        // renderpasses might have parameters set up in their YAML config. These get translated to
        // descriptor layouts, UBOs and descriptor sets
        logger.debug("Creating VulkanUBO for $name")
        // create UBO
        val ubo = VulkanUBO(device)

        ubo.name = "ShaderParameters-$name"
        passConfig.parameters.forEach { entry ->
            // Entry could be created in Java, so we check for both Java and Kotlin strings
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            val value = if (entry.value is String || entry.value is java.lang.String) {
                val s = entry.value as String
                val split = s.split(",").map { it.trim().trimStart().toFloat() }.toFloatArray()

                when(split.size) {
                    2 -> Vector2f(split[0], split[1])
                    3 -> Vector3f(split[0], split[1], split[2])
                    4 -> Vector4f(split[0], split[1], split[2], split[3])
                    else -> throw IllegalStateException("Dont know how to handle ${split.size} elements in Shader Parameter split")
                }                } else if (entry.value is Double) {
                (entry.value as Double).toFloat()
            } else {
                entry.value
            }

            val settingsKey = when {
                entry.key.startsWith("System") -> "System.${entry.key.substringAfter("System.")}"
                entry.key.startsWith("Global") -> "Renderer.${entry.key.substringAfter("Global.")}"
                entry.key.startsWith("Pass") -> "Renderer.$name.${entry.key.substringAfter("Pass.")}"
                else -> "Renderer.$name.${entry.key}"
            }

            if (!entry.key.startsWith("Global") && !entry.key.startsWith("Pass.") && !entry.key.startsWith("System.")) {
                settings.setIfUnset(settingsKey, value)
            }

            ubo.add(entry.key, { settings.get(settingsKey) })
        }

        if(logger.isDebugEnabled) {
            logger.debug("Members are: {}", ubo.membersAndContent())
            logger.debug("Allocating VulkanUBO memory now, space needed: {}", ubo.getSize())
        }

        ubo.createUniformBuffer()

        // create descriptor set layout
//            val dsl = VU.createDescriptorSetLayout(device,
//                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, 1)
        val dsl = device.createDescriptorSetLayout(
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1)),
            0, VK_SHADER_STAGE_ALL)

        val ds = device.createDescriptorSet(dsl,
            1, ubo.descriptor, type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)

        // populate descriptor set
        ubo.populate()

        UBOs.put("ShaderParameters-$name", ubo)
        descriptorSets.put("ShaderParameters-$name", ds)

        logger.debug("Created DSL $dsl for $name, VulkanUBO has ${passConfig.parameters.count()} members")
        descriptorSetLayouts.putIfAbsent("ShaderParameters-$name", dsl)
        ownDescriptorSetLayouts.add(dsl)
    }

    /**
     * Initialiases descriptor set layouts for [graphics.scenery.ShaderProperty]s.
     */
    fun initializeShaderPropertyDescriptorSetLayout(): Long {
        // this creates a shader property UBO for items marked @ShaderProperty in node
        val alreadyCreated = descriptorSetLayouts.containsKey("ShaderProperties-$name")

        // returns a ordered list of the members of the ShaderProperties struct
        return if(!alreadyCreated) {
            // create descriptor set layout
            val dsl = device.createDescriptorSetLayout(
                listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1)),
                binding = 0, shaderStages = VK_SHADER_STAGE_ALL)

            logger.debug("Created Shader Property DSL ${dsl.toHexString()} for $name")
            descriptorSetLayouts.putIfAbsent("ShaderProperties-$name", dsl)
            ownDescriptorSetLayouts.add(dsl)
            dsl
        } else {
            descriptorSetLayouts.getOrElse("ShaderProperties-$name") {
                throw IllegalStateException("ShaderProperties-$name does not exist in descriptor set layouts for $this.")
            }
        }
    }

    fun getDescriptorSetLayoutForTexture(name: String, node: Node): Pair<Long, List<VulkanShaderModule.UBOSpec>>? {
        logger.debug("Looking for texture name $name in descriptor specs")
        val set = getActivePipeline(node).descriptorSpecs.entries
            .groupBy { it.value.set }
            .toSortedMap()
            .filter { it.value.any { spec -> spec.value.name == name } }
            .entries.firstOrNull()?.value?.sortedBy { it.value.binding }?.map { it.value }?.toList()

        val key = set?.firstOrNull()?.name?: return null
        val dsl = descriptorSetLayouts[key] ?: return null

        logger.debug("Found DSL key: $key")

        return dsl to set
    }

    /**
     * Returns the order of shader properties as a map for a given [node] as required by the shader file.
     */
    fun getShaderPropertyOrder(node: Node): Map<String, Int> {
        // this creates a shader property UBO for items marked @ShaderProperty in node
        logger.debug("specs: ${this.pipelines.getValue("preferred-${node.uuid}").descriptorSpecs}")
        val shaderPropertiesSpec = this.pipelines.getValue("preferred-${node.uuid}").descriptorSpecs.filter { it.key == "ShaderProperties" }.map { it.value.members }

        if(shaderPropertiesSpec.count() == 0) {
            logger.debug("Warning: Shader file uses no declared shader properties, despite the class declaring them.")
            return emptyMap()
        }

        // returns a ordered list of the members of the ShaderProperties struct
        return shaderPropertiesSpec
            .flatMap { it.values }
            .map { it.name to it.offset.toInt() }
            .toMap()
    }

    /**
     * Updates all shader parameters.
     */
    fun updateShaderParameters() {
        UBOs.forEach { uboName, ubo ->
            if(uboName.startsWith("ShaderParameters-")) {
                ubo.populate()
            }
        }
    }

    /**
     * Initialiases the default [VulkanPipeline] for this renderpass.
     */
    fun initializeDefaultPipeline() {
        val shaders = Shaders.ShadersFromFiles(passConfig.shaders.map { "shaders/$it" }.toTypedArray())
        val shaderModules = ShaderType.values().mapNotNull { type ->
            try {
                VulkanShaderModule.getFromCacheOrCreate(device, "main", shaders.get(Shaders.ShaderTarget.Vulkan, type))
            } catch (e: ShaderNotFoundException) {
                logger.debug("Shader not found: $type - this is normal if there are no errors reported")
                null
            }
        }

        initializeInputAttachmentDescriptorSetLayouts(shaderModules)
        initializePipeline("default", shaderModules)
    }

    /**
     * Initialiases a custom [VulkanPipeline] with [pipelineName], built out of the [shaders] for a specific [vertexInputType].
     * The pipeline settings are customizable using the lambda [settings].
     */
    fun initializePipeline(pipelineName: String = "default", shaders: List<VulkanShaderModule>,
                           vertexInputType: VulkanRenderer.VertexDescription? = vertexDescriptors.getValue(VulkanRenderer.VertexDataKinds.PositionNormalTexcoord),
                           settings: (VulkanPipeline) -> Any = {}) {
        val reqDescriptorLayouts = ArrayList<Long>()

        val framebuffer = output.values.first()
        val p = VulkanPipeline(device, this, framebuffer.renderPass.get(0), pipelineCache)

        p.addShaderStages(shaders)

        logger.debug("${descriptorSetLayouts.count()} DSLs are available: ${descriptorSetLayouts.keys.joinToString(", ")}")

        val blendMasks = VkPipelineColorBlendAttachmentState.calloc(framebuffer.colorAttachmentCount())
        (0 until framebuffer.colorAttachmentCount()).forEach {
            if(passConfig.renderTransparent) {
                blendMasks[it]
                    .blendEnable(true)
                    .colorBlendOp(passConfig.colorBlendOp.toVulkan())
                    .srcColorBlendFactor(passConfig.srcColorBlendFactor.toVulkan())
                    .dstColorBlendFactor(passConfig.dstColorBlendFactor.toVulkan())
                    .alphaBlendOp(passConfig.alphaBlendOp.toVulkan())
                    .srcAlphaBlendFactor(passConfig.srcAlphaBlendFactor.toVulkan())
                    .dstAlphaBlendFactor(passConfig.dstAlphaBlendFactor.toVulkan())
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            } else {
                blendMasks[it]
                    .blendEnable(false)
                    .colorWriteMask(0xF)
            }
        }

        p.colorBlendState.pAttachments()?.free()
        p.colorBlendState
            .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
            .pNext(NULL)
            .pAttachments(blendMasks)

        p.depthStencilState
            .depthTestEnable(passConfig.depthTestEnabled)
            .depthWriteEnable(passConfig.depthWriteEnabled)

        p.descriptorSpecs.entries
            .groupBy { it.value.set }
            .toSortedMap()
            .forEach { (setId, group) ->
                logger.debug("${this.name}: Initialising DSL for set $setId with ${group.sortedBy { it.value.binding }.joinToString(", ") { "${it.value.name} (${it.value.set}/${it.value.binding})" }} (${group.size} members)")
                reqDescriptorLayouts.add(initializeDescriptorSetLayoutForSpecs(setId, group.sortedBy { it.value.binding }))
            }

        settings.invoke(p)

        if(logger.isDebugEnabled) {
            logger.debug("DS are: ${p.descriptorSpecs.entries.sortedBy { it.value.binding }.sortedBy { it.value.set }.joinToString { "${it.key} (set=${it.value.set}, binding=${it.value.binding}, type=${it.value.type})" } }")
        }

        logger.debug("Required DSLs: ${reqDescriptorLayouts.joinToString { it.toHexString() } }")

        when {
            (passConfig.type == RenderConfigReader.RenderpassType.quad && vertexInputType != null)
                && shaders.first().type != ShaderType.ComputeShader -> {
                p.rasterizationState.cullMode(VK_CULL_MODE_FRONT_BIT)
                p.rasterizationState.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)

                p.createPipelines(
                    vi = vertexDescriptors.getValue(VulkanRenderer.VertexDataKinds.None).state,
                    descriptorSetLayouts = reqDescriptorLayouts,
                    onlyForTopology = GeometryType.TRIANGLES)
            }

            (passConfig.type == RenderConfigReader.RenderpassType.geometry
                || passConfig.type == RenderConfigReader.RenderpassType.lights)
                && shaders.first().type != ShaderType.ComputeShader && vertexInputType != null -> {
                p.createPipelines(
                    vi = vertexInputType.state,
                    descriptorSetLayouts = reqDescriptorLayouts)
            }

            passConfig.type == RenderConfigReader.RenderpassType.compute
                || shaders.first().type == ShaderType.ComputeShader -> {
                p.createPipelines(
                    descriptorSetLayouts = reqDescriptorLayouts,
                    type = VulkanPipeline.PipelineType.Compute,
                    vi = null)
            }
        }

        logger.debug("Prepared pipeline $pipelineName for $name")

        pipelines.put(pipelineName, p)?.close()
    }

    /**
     * Invalides all command buffers of this renderpass.
     */
    fun invalidateCommandBuffers() {
        vulkanMetadata.renderLists.keys.forEach { it.stale = true }
    }

    private fun initializeDescriptorSetLayoutForSpecs(setId: Long, specs: List<MutableMap.MutableEntry<String, VulkanShaderModule.UBOSpec>>): Long {
        val contents = specs.map { s ->
            val spec = s.value
            logger.debug("$name: Looking at ${spec.name} with size ${spec.size} and ${spec.members.size} members")

            when {
                spec.name == "Matrices" ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1))

                spec.name == "MaterialProperties" ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1))

                spec.name.startsWith("Input")
                    && (spec.type == VulkanShaderModule.UBOSpecType.SampledImage1D
                    || spec.type == VulkanShaderModule.UBOSpecType.SampledImage2D
                    || spec.type == VulkanShaderModule.UBOSpecType.SampledImage3D) ->
                    if(spec.members.isNotEmpty()) {
                        spec.members.map { Pair(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1) }.toList()
                    } else {
                        listOf(Pair(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, maxOf(1, spec.size)))
                    }

                spec.name.startsWith("Input")
                    && (spec.type == VulkanShaderModule.UBOSpecType.Image1D
                    || spec.type == VulkanShaderModule.UBOSpecType.Image2D
                    || spec.type == VulkanShaderModule.UBOSpecType.Image3D) ->
                    if(spec.members.isNotEmpty()) {
                        spec.members.map { Pair(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1) }.toList()
                    } else {
                        listOf(Pair(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, maxOf(1, spec.size)))
                    }

                spec.name.startsWith("Output")
                    && (spec.type == VulkanShaderModule.UBOSpecType.Image1D
                    || spec.type == VulkanShaderModule.UBOSpecType.Image2D
                    || spec.type == VulkanShaderModule.UBOSpecType.Image3D) ->
                    if(spec.members.isNotEmpty()) {
                        spec.members.map { Pair(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1) }.toList()
                    } else {
                        listOf(Pair(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, maxOf(1, spec.size)))
                    }

                spec.name == "ShaderParameters" ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1))

                spec.name == "ShaderProperties" ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1))

                spec.type == VulkanShaderModule.UBOSpecType.StorageBuffer ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, spec.size))

                spec.type == VulkanShaderModule.UBOSpecType.StorageBufferDynamic ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC, spec.size))

                spec.type == VulkanShaderModule.UBOSpecType.Image1D
                    || spec.type == VulkanShaderModule.UBOSpecType.Image2D
                    || spec.type == VulkanShaderModule.UBOSpecType.Image3D ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, maxOf(1, spec.size)))

                spec.type == VulkanShaderModule.UBOSpecType.SampledImage1D
                    || spec.type == VulkanShaderModule.UBOSpecType.SampledImage2D
                    || spec.type == VulkanShaderModule.UBOSpecType.SampledImage3D ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, maxOf(1, spec.size)))

                else ->
                    listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1))
            }
        }.flatten()

        val dsl = device.createDescriptorSetLayout(contents, 0, shaderStages = VK_SHADER_STAGE_ALL)
        // destroy descriptor set layout if there was a previously associated one,
        // and add the new one
        descriptorSetLayouts.put(specs.first().value.name, dsl)?.let { dslOld ->
            // TODO: Figure out whether they should actually be deleted, or just marked for garbage collection
            oldDescriptorSetLayouts.add(specs.first().value.name to dslOld)
        }

        logger.debug("$name: Initialiased DSL ${dsl.toHexString()} for ${specs.firstOrNull()?.value?.name}, set=$setId, type=${contents.firstOrNull()?.first}")

        return dsl
    }

    /**
     * Returns the default output [VulkanFramebuffer] of this renderpass.
     */
    fun getOutput(): VulkanFramebuffer {
        val fb = if(isViewportRenderpass) {
            val pos = currentPosition
            currentPosition = (currentPosition + 1).rem(commandBufferCount)

            output.getValue("Viewport-$pos")
        } else {
            output.values.first()
        }

        return fb
    }

    /**
     * Returns the [commandBufferBacking]'s current read position. Used e.g.
     * to determine the most currently rendered swapchain image for a viewport pass.
     */
    fun getReadPosition() = commandBufferBacking.currentReadPosition - 1

    fun getWritePosition() = commandBufferBacking.currentWritePosition - 1

    /**
     * Returns the active [VulkanPipeline] for [forNode], if it has a preferred pipeline,
     * or the default one if not.
     */
    fun getActivePipeline(forNode: Node): VulkanPipeline {
        return pipelines.getOrDefault("preferred-${forNode.uuid}", getDefaultPipeline())
    }

    /**
     * Removes any preferred [VulkanPipeline] for the node given in [forNode].
     */
    fun removePipeline(forNode: Node): Boolean {
        return pipelines.remove("preferred-${forNode.uuid}") != null
    }

    /**
     * Returns this renderpasses' default pipeline.
     */
    fun getDefaultPipeline(): VulkanPipeline {
        return pipelines.getValue("default")
    }

    /**
     * Closes this renderpass and deallocates its resources.
     */
    override fun close() {
        logger.debug("Closing renderpass $name...")
        output.forEach { it.value.close() }
        pipelines.forEach { it.value.close() }
        UBOs.forEach { it.value.close() }
        ownDescriptorSetLayouts.forEach {
            logger.debug("Destroying DSL ${it.toHexString()}")
            device.removeDescriptorSetLayout(it)
        }
        descriptorSetLayouts.clear()
        oldDescriptorSetLayouts.forEach {
            logger.debug("Destroying GC'd DSL ${it.first} ${it.second.toHexString()}")
            device.removeDescriptorSetLayout(it.second)
        }
        oldDescriptorSetLayouts.clear()

        vulkanMetadata.close()

        logger.debug("Closing command buffer backings")
        for(i in 1..commandBufferBacking.size) {
            commandBufferBacking.get().close()
        }

        commandBufferBacking.reset()

        logger.debug("Destroying semaphores")
        semaphoreBacking.close()

        memFree(waitSemaphores)
        memFree(signalSemaphores)
        memFree(waitStages)
        memFree(submitCommandBuffers)
    }

    companion object {
        val logger by LazyLogger()
        var pipelineCache = -1L

        fun createPipelineCache(device: VulkanDevice) {
            val pipelineCacheInfo = VkPipelineCacheCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO)
                .pNext(NULL)

            if (pipelineCache != -1L) {
                vkDestroyPipelineCache(device.vulkanDevice, pipelineCache, null)
            }

            pipelineCache = VU.getLong("create pipeline cache",
                { vkCreatePipelineCache(device.vulkanDevice, pipelineCacheInfo, null, this) },
                { pipelineCacheInfo.free() })
        }

        fun destroyPipelineCache(device: VulkanDevice) {
            if(pipelineCache != -1L) {
                vkDestroyPipelineCache(device.vulkanDevice, pipelineCache, null)
                pipelineCache = -1L
            }
        }

        fun prepareRenderpassesFromConfig(
            config: RenderConfigReader.RenderConfig,
            device: VulkanDevice,
            commandPools: VulkanRenderer.CommandPools,
            queue: VkQueue,
            vertexDescriptors: ConcurrentHashMap<VulkanRenderer.VertexDataKinds, VulkanRenderer.VertexDescription>,
            swapchain: Swapchain,
            windowWidth: Int,
            windowHeight: Int,
            settings: Settings
        ): Pair<List<String>, LinkedHashMap<String, VulkanRenderpass>> {
            val renderpasses = LinkedHashMap<String, VulkanRenderpass>()
            // create all renderpasses first
            val framebuffers = ConcurrentHashMap<String, VulkanFramebuffer>()

            val flow = config.createRenderpassFlow()
            logger.debug("Renderpasses to be run: ${flow.joinToString(", ")}")

            config.createRenderpassFlow().map { passName ->
                val passConfig = config.renderpasses.getValue(passName)
                val pass = VulkanRenderpass(passName, config, device, pipelineCache, vertexDescriptors, swapchain.images.size)

                var width = windowWidth
                var height = windowHeight

                // create framebuffer
                with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) {
                    config.rendertargets.filter { it.key == passConfig.output.name }.map { rt ->
                        width = (settings.get<Float>("Renderer.SupersamplingFactor") * windowWidth * rt.value.size.first).toInt()
                        height = (settings.get<Float>("Renderer.SupersamplingFactor") * windowHeight * rt.value.size.second).toInt()

                        logger.info("Creating render framebuffer ${rt.key} for pass $passName (${width}x${height})")

                        settings.set("Renderer.$passName.displayWidth", width)
                        settings.set("Renderer.$passName.displayHeight", height)

                        if (framebuffers.containsKey(rt.key)) {
                            logger.info("Reusing already created framebuffer")
                            pass.output.put(rt.key, framebuffers.getValue(rt.key))
                        } else {

                            // create framebuffer -- don't clear it, if blitting is needed
                            val framebuffer = VulkanFramebuffer(device, commandPools.Standard,
                                width, height, this,
                                shouldClear = !passConfig.blitInputs,
                                sRGB = config.sRGB)

                            rt.value.attachments.forEach { att ->
                                logger.info(" + attachment ${att.key}, ${att.value.name}")

                                when (att.value) {
                                    RenderConfigReader.TargetFormat.RGBA_Float32 -> framebuffer.addFloatRGBABuffer(att.key, 32)
                                    RenderConfigReader.TargetFormat.RGBA_Float16 -> framebuffer.addFloatRGBABuffer(att.key, 16)

                                    RenderConfigReader.TargetFormat.RGB_Float32 -> framebuffer.addFloatRGBBuffer(att.key, 32)
                                    RenderConfigReader.TargetFormat.RGB_Float16 -> framebuffer.addFloatRGBBuffer(att.key, 16)

                                    RenderConfigReader.TargetFormat.RG_Float32 -> framebuffer.addFloatRGBuffer(att.key, 32)
                                    RenderConfigReader.TargetFormat.RG_Float16 -> framebuffer.addFloatRGBuffer(att.key, 16)

                                    RenderConfigReader.TargetFormat.RGBA_UInt16 -> framebuffer.addUnsignedByteRGBABuffer(att.key, 16)
                                    RenderConfigReader.TargetFormat.RGBA_UInt8 -> framebuffer.addUnsignedByteRGBABuffer(att.key, 8)
                                    RenderConfigReader.TargetFormat.R_UInt16 -> framebuffer.addUnsignedByteRBuffer(att.key, 16)
                                    RenderConfigReader.TargetFormat.R_UInt8 -> framebuffer.addUnsignedByteRBuffer(att.key, 8)

                                    RenderConfigReader.TargetFormat.Depth32 -> framebuffer.addDepthBuffer(att.key, 32)
                                    RenderConfigReader.TargetFormat.Depth24 -> framebuffer.addDepthBuffer(att.key, 24)
                                    RenderConfigReader.TargetFormat.R_Float16 -> framebuffer.addFloatBuffer(att.key, 16)
                                }

                            }

                            framebuffer.createRenderpassAndFramebuffer()
                            device.tag(framebuffer.framebuffer.get(0), VulkanDevice.VulkanObjectType.Framebuffer, "Framebuffer for ${rt.key}")

                            pass.output[rt.key] = framebuffer
                            framebuffers.put(rt.key, framebuffer)
                        }
                    }

                    pass.commandBufferCount = swapchain.images.size

                    if (passConfig.output.name == "Viewport") {
                        // create viewport renderpass with swapchain image-derived framebuffer
                        pass.isViewportRenderpass = true

                        width = windowWidth
                        height = windowHeight

                        swapchain.images.forEachIndexed { i, _ ->
                            val fb = VulkanFramebuffer(device, commandPools.Standard,
                                width, height, this@with, sRGB = config.sRGB)

                            fb.addSwapchainAttachment("swapchain-$i", swapchain, i)
                            fb.addDepthBuffer("swapchain-$i-depth", 32)
                            fb.createRenderpassAndFramebuffer()
                            device.tag(fb.framebuffer.get(0), VulkanDevice.VulkanObjectType.Framebuffer, "Framebuffer for swapchain image $i")

                            pass.output["Viewport-$i"] = fb
                        }
                    }

                    pass.vulkanMetadata.clearValues?.free()
                    if(!passConfig.blitInputs) {
                        pass.vulkanMetadata.clearValues = VkClearValue.calloc(pass.output.values.first().attachments.count())
                        pass.vulkanMetadata.clearValues?.let { clearValues ->

                            pass.output.values.first().attachments.values.forEachIndexed { i, att ->
                                when (att.type) {
                                    VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> {
                                        pass.passConfig.clearColor.get(clearValues[i].color().float32())
                                    }
                                    VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> {
                                        clearValues[i].depthStencil().set(pass.passConfig.depthClearValue, 0)
                                    }
                                }
                            }
                        }
                    } else {
                        pass.vulkanMetadata.clearValues = null
                    }

                    pass.vulkanMetadata.renderArea.extent().set(
                        (pass.passConfig.viewportSize.first * width).toInt(),
                        (pass.passConfig.viewportSize.second * height).toInt())
                    pass.vulkanMetadata.renderArea.offset().set(
                        (pass.passConfig.viewportOffset.first * width).toInt(),
                        (pass.passConfig.viewportOffset.second * height).toInt())
                    logger.debug("Render area for $passName: ${pass.vulkanMetadata.renderArea.extent().width()}x${pass.vulkanMetadata.renderArea.extent().height()}")

                    pass.vulkanMetadata.viewport[0].set(
                        (pass.passConfig.viewportOffset.first * width),
                        (pass.passConfig.viewportOffset.second * height),
                        (pass.passConfig.viewportSize.first * width),
                        (pass.passConfig.viewportSize.second * height),
                        0.0f, 1.0f)

                    pass.vulkanMetadata.scissor[0].extent().set(
                        (pass.passConfig.viewportSize.first * width).toInt(),
                        (pass.passConfig.viewportSize.second * height).toInt())

                    pass.vulkanMetadata.scissor[0].offset().set(
                        (pass.passConfig.viewportOffset.first * width).toInt(),
                        (pass.passConfig.viewportOffset.second * height).toInt())

                    pass.vulkanMetadata.eye.put(0, pass.passConfig.eye)

                    endCommandBuffer(device, commandPools.Standard, queue, flush = true)
                }

                renderpasses.put(passName, pass)
            }

            // connect inputs with each othe
            renderpasses.forEach { pass ->
                val passConfig = config.renderpasses.getValue(pass.key)

                passConfig.inputs?.forEach { inputTarget ->
                    val targetName = if(inputTarget.name.contains(".")) {
                        inputTarget.name.substringBefore(".")
                    } else {
                        inputTarget.name
                    }
                    renderpasses.filter {
                        it.value.output.keys.contains(targetName)
                    }.forEach { pass.value.inputs[inputTarget.name] = it.value.output.getValue(targetName) }
                }

                with(pass.value) {
                    initializeShaderParameterDescriptorSetLayouts(settings)

                    initializeDefaultPipeline()
                }
            }

            return flow to renderpasses
        }
    }
}
