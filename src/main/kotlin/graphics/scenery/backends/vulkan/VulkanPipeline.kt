package graphics.scenery.backends.vulkan

import graphics.scenery.GeometryType
import graphics.scenery.Node
import graphics.scenery.backends.ShaderConsistencyException
import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.IntBuffer
import java.util.*
import kotlin.collections.LinkedHashMap

/**
 * Vulkan Pipeline class.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VulkanPipeline(val device: VulkanDevice, val renderpass: VulkanRenderpass, val vulkanRenderpass: Long, val pipelineCache: Long? = null): AutoCloseable {
    private val logger by LazyLogger()
    var type: PipelineType = PipelineType.Graphics

    enum class PipelineType { Graphics, Compute }

    var pipeline = HashMap<GeometryType, VulkanRenderer.Pipeline>()
    var descriptorSpecs = LinkedHashMap<String, VulkanShaderModule.UBOSpec>()
    var pushConstantSpecs = LinkedHashMap<String, VulkanShaderModule.PushConstantSpec>()

    val inputAssemblyState: VkPipelineInputAssemblyStateCreateInfo = VkPipelineInputAssemblyStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        .primitiveRestartEnable(false)
        .pNext(NULL)

    val rasterizationState: VkPipelineRasterizationStateCreateInfo = VkPipelineRasterizationStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
        .pNext(NULL)
        .polygonMode(VK_POLYGON_MODE_FILL)
        .cullMode(VK_CULL_MODE_BACK_BIT)
        .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
        .depthClampEnable(false)
        .rasterizerDiscardEnable(false)
        .depthBiasEnable(false)
        .lineWidth(1.0f)

    val colorWriteMask: VkPipelineColorBlendAttachmentState.Buffer = VkPipelineColorBlendAttachmentState.calloc(1)
        .blendEnable(false)
        .colorWriteMask(0xF) // this means RGBA writes

    val colorBlendState: VkPipelineColorBlendStateCreateInfo = VkPipelineColorBlendStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
        .pNext(NULL)
        .pAttachments(colorWriteMask)

    val viewportState: VkPipelineViewportStateCreateInfo = VkPipelineViewportStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
        .pNext(NULL)
        .viewportCount(1)
        .scissorCount(1)

    val pDynamicStates: IntBuffer = memAllocInt(2).apply {
        put(0, VK_DYNAMIC_STATE_VIEWPORT)
        put(1, VK_DYNAMIC_STATE_SCISSOR)
    }

    val dynamicState: VkPipelineDynamicStateCreateInfo = VkPipelineDynamicStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
        .pNext(NULL)
        .pDynamicStates(pDynamicStates)

    var depthStencilState: VkPipelineDepthStencilStateCreateInfo = VkPipelineDepthStencilStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
        .pNext(NULL)
        .depthTestEnable(true)
        .depthWriteEnable(true)
        .depthCompareOp(VK_COMPARE_OP_LESS)
        .depthBoundsTestEnable(false)
        .minDepthBounds(0.0f)
        .maxDepthBounds(1.0f)
        .stencilTestEnable(false)

    val multisampleState: VkPipelineMultisampleStateCreateInfo = VkPipelineMultisampleStateCreateInfo.calloc()
        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
        .pNext(NULL)
        .pSampleMask(null)
        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

    val shaderStages = ArrayList<VulkanShaderModule>(2)

    /**
     * Adds the shader stages given in [shaderModules] to this pipeline. One or more stages need
     * to be given here.
     */
    fun addShaderStages(shaderModules: List<VulkanShaderModule>) {
        shaderStages.clear()

        shaderModules.forEach {
            this.shaderStages.add(it)

            it.uboSpecs.forEach { uboName, ubo ->
                descriptorSpecs[uboName]?.members?.putAll(ubo.members) ?: descriptorSpecs.put(uboName, ubo)
            }

            it.pushConstantSpecs.forEach { name, pushConstant ->
                pushConstantSpecs[name]?.members?.putAll(pushConstant.members) ?: pushConstantSpecs.put(name, pushConstant)
            }
        }

        val usedDescriptorSetCount = if(descriptorSpecs.isEmpty()) { 0 } else { descriptorSpecs.map { it.value.set }.distinct().size - 1 }
        val maxDescriptorSetNumber = (descriptorSpecs.map { it.value.set }.maxOrNull() ?: 0L).toInt()

        if(usedDescriptorSetCount != maxDescriptorSetNumber) {
            throw ShaderConsistencyException("Shader contains non-consecutive number of descriptor sets! Look for layout(set = $maxDescriptorSetNumber, binding = ...) in the shader code of ${shaderModules.flatMap { listOf(it.sp.codePath, it.sp.spirvPath) }.filterNotNull().joinToString(", ") } and check if all set numbers before $maxDescriptorSetNumber are actually used ($maxDescriptorSetNumber is the maximum set declared, but only $usedDescriptorSetCount sets are used).")
        }
    }

    /**
     * Creates a new Vulkan graphics or compute pipeline with vertex input state [vi],
     * and [descriptorSetLayouts]. If the pipeline should only be created for a given topology [onlyForTopology],
     * this can be set to non-null. If a compute pipeline should be created, the [type] can be set to
     * [PipelineType.Compute]. The function will store the pipeline(s) internally and make them accessible via
     * [getPipelineForGeometryType].
     */
    fun createPipelines(
        descriptorSetLayouts: List<Long>,
        type: PipelineType = PipelineType.Graphics,
        vi: VkPipelineVertexInputStateCreateInfo?,
        onlyForTopology: GeometryType? = null
    ) {
        val setLayouts = memAllocLong(descriptorSetLayouts.size).put(descriptorSetLayouts.toLongArray())
        setLayouts.flip()

        val pushConstantRanges = if(pushConstantSpecs.size > 0) {
            val pcr = VkPushConstantRange.calloc(pushConstantSpecs.size)
            pushConstantSpecs.entries.forEachIndexed { i, p ->
                val offset = p.value.members.map { it.value.offset }.minOrNull() ?: 0L
                val size = p.value.members.map { it.value.range }.sum()

                logger.debug("Push constant: id $i name=${p.key} offset=$offset size=$size")

                pcr.get(i)
                    .offset(offset.toInt())
                    .size(size.toInt())
                    .stageFlags(VK_SHADER_STAGE_ALL)
            }

            pcr
        } else {
            null
        }

        val pPipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pNext(NULL)
            .pSetLayouts(setLayouts)
            .pPushConstantRanges(pushConstantRanges)

        val layout = VU.getLong("vkCreatePipelineLayout",
            { vkCreatePipelineLayout(device.vulkanDevice, pPipelineLayoutCreateInfo, null, this) },
            { pushConstantRanges?.free(); pPipelineLayoutCreateInfo.free(); memFree(setLayouts); })

        val stages = VkPipelineShaderStageCreateInfo.calloc(shaderStages.size)
        shaderStages.forEachIndexed { i, shaderStage ->
            stages.put(i, shaderStage.shader)
        }

        val (p, pipelineCreateInfo) = when(type) {
            PipelineType.Graphics -> {
                val pipelineCreateInfo = VkGraphicsPipelineCreateInfo.calloc(1)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pNext(NULL)
                    .layout(layout)
                    .renderPass(vulkanRenderpass)
                    .pVertexInputState(vi)
                    .pInputAssemblyState(inputAssemblyState)
                    .pRasterizationState(rasterizationState)
                    .pColorBlendState(colorBlendState)
                    .pMultisampleState(multisampleState)
                    .pViewportState(viewportState)
                    .pDepthStencilState(depthStencilState)
                    .pStages(stages)
                    .pDynamicState(dynamicState)
                    .flags(VK_PIPELINE_CREATE_ALLOW_DERIVATIVES_BIT)
                    .subpass(0)

                if (onlyForTopology != null) {
                    inputAssemblyState.topology(onlyForTopology.asVulkanTopology())
                }

                VU.getLong("vkCreateGraphicsPipelines for ${renderpass.name} ($vulkanRenderpass)", {
                    vkCreateGraphicsPipelines(device.vulkanDevice, pipelineCache
                        ?: VK_NULL_HANDLE, pipelineCreateInfo, null, this)
                }, {}) to pipelineCreateInfo
            }

            PipelineType.Compute -> {
                val pipelineCreateInfo = VkComputePipelineCreateInfo.calloc(1)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .pNext(NULL)
                    .layout(layout)
                    .stage(stages.first())

                VU.getLong("vkCreateComputePipelines for ${renderpass.name} ($vulkanRenderpass)", {
                    vkCreateComputePipelines(device.vulkanDevice, pipelineCache
                        ?: VK_NULL_HANDLE, pipelineCreateInfo, null, this)
                }, {}) to pipelineCreateInfo
            }
        }

        val vkp = VulkanRenderer.Pipeline()
        vkp.layout = layout
        vkp.pipeline = p
        vkp.type = type

        this.type = type
        this.pipeline[GeometryType.TRIANGLES] = vkp

        logger.debug("Pipeline needs descriptor sets ${descriptorSpecs.keys.joinToString(", ")}")

        // derivative pipelines only make sense for graphics pipelines in our case
        if(onlyForTopology == null && pipelineCreateInfo is VkGraphicsPipelineCreateInfo.Buffer) {
            // create pipelines for other topologies as well
            GeometryType.values().forEach { topology ->
                if (topology == GeometryType.TRIANGLES) {
                    return@forEach
                }

                inputAssemblyState.topology(topology.asVulkanTopology()).pNext(NULL)

                pipelineCreateInfo
                    .pInputAssemblyState(inputAssemblyState)
                    .basePipelineHandle(vkp.pipeline)
                    .basePipelineIndex(-1)
                    .flags(VK_PIPELINE_CREATE_DERIVATIVE_BIT)

                val derivativeP = VU.getLong("vkCreateGraphicsPipelines(derivative) for ${renderpass.name} ($vulkanRenderpass)",
                    { vkCreateGraphicsPipelines(device.vulkanDevice, pipelineCache ?: VK_NULL_HANDLE, pipelineCreateInfo, null, this) }, {})

                val derivativePipeline = VulkanRenderer.Pipeline()
                derivativePipeline.layout = layout
                derivativePipeline.pipeline = derivativeP

                this.pipeline[topology] = derivativePipeline
            }
        }

        logger.debug("Created $this for renderpass ${renderpass.name} (${vulkanRenderpass.toHexString()}) with pipeline layout ${layout.toHexString()} (${if(onlyForTopology == null) { "Derivatives:" + this.pipeline.keys.joinToString(", ")} else { "no derivatives, only ${this.pipeline.keys.first()}" }})")

        pipelineCreateInfo.free()
        stages.free()
    }

    fun getPipelineForGeometryType(type: GeometryType): VulkanRenderer.Pipeline {
        return pipeline.getOrElse(type, {
            logger.error("Pipeline $this does not contain a fitting pipeline for $type, return triangle pipeline")
            pipeline.getOrElse(GeometryType.TRIANGLES, { throw IllegalStateException("Default triangle pipeline not present for $this") })
        })
    }

    private fun GeometryType.asVulkanTopology(): Int {
        return when(this) {
            GeometryType.TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN
            GeometryType.TRIANGLES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
            GeometryType.LINE -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST
            GeometryType.POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST
            GeometryType.LINES_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY
            GeometryType.LINE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY
            GeometryType.POLYGON -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
            GeometryType.TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
        }
    }

    fun orderedDescriptorSpecs(): List<MutableMap.MutableEntry<String, VulkanShaderModule.UBOSpec>> {
        return descriptorSpecs.entries.sortedBy { it.value.binding }.sortedBy { it.value.set }
    }

    override fun toString(): String {
        return "VulkanPipeline (${pipeline.map { "${it.key.name} -> ${String.format("0x%X", it.value.pipeline)}"}.joinToString(", ")})"
    }

    override fun close() {
        val removedLayouts = ArrayList<Long>()

        pipeline.forEach { _, pipeline ->
            vkDestroyPipeline(device.vulkanDevice, pipeline.pipeline, null)

            if(!removedLayouts.contains(pipeline.layout)) {
                vkDestroyPipelineLayout(device.vulkanDevice, pipeline.layout, null)
                removedLayouts.add(pipeline.layout)
            }
        }

        inputAssemblyState.free()
        rasterizationState.free()
        depthStencilState.free()
        colorBlendState.pAttachments()?.free()
        colorBlendState.free()
        viewportState.free()
        dynamicState.free()
        memFree(pDynamicStates)
        multisampleState.free()
    }
}
