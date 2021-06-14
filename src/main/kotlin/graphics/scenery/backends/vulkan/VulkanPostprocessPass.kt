package graphics.scenery.backends.vulkan

import graphics.scenery.geometry.GeometryType
import graphics.scenery.Node
import graphics.scenery.backends.vulkan.VulkanNodeHelpers.rendererMetadata
import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10

/**
 * Helper object for recording postprocessing passes.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
object VulkanPostprocessPass {
    val logger by LazyLogger()

    /**
     * Records a new command buffer for [pass] into [commandBuffer]. Eventually necessary further buffers
     * are allocated from [commandPools]. [sceneUBOs] need to be given, as well as the available global
     * [descriptorSets].
     */
    fun record(
        pass: VulkanRenderpass,
        commandBuffer: VulkanCommandBuffer,
        commandPools: VulkanRenderer.CommandPools,
        sceneUBOs: List<Node>,
        descriptorSets: Map<String, Long>
    ) {
        val target = pass.getOutput()

        logger.trace("Creating postprocessing command buffer for {}/{} ({} attachments)", pass.name, target, target.attachments.count())

        pass.vulkanMetadata.renderPassBeginInfo
            .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
            .pNext(MemoryUtil.NULL)
            .renderPass(target.renderPass.get(0))
            .framebuffer(target.framebuffer.get(0))
            .renderArea(pass.vulkanMetadata.renderArea)
            .pClearValues(pass.vulkanMetadata.clearValues)

        if(!commandBuffer.stale) {
            return
        }

        // prepare command buffer and start recording
        with(commandBuffer.prepareAndStartRecording(commandPools.Render)) {
            VK10.vkCmdBeginRenderPass(this, pass.vulkanMetadata.renderPassBeginInfo, VK10.VK_SUBPASS_CONTENTS_INLINE)

            VK10.vkCmdSetViewport(this, 0, pass.vulkanMetadata.viewport)
            VK10.vkCmdSetScissor(this, 0, pass.vulkanMetadata.scissor)

            val pipeline = pass.getDefaultPipeline()
            val vulkanPipeline = pipeline.getPipelineForGeometryType(GeometryType.TRIANGLES)

            if (pass.vulkanMetadata.descriptorSets.capacity() != pipeline.descriptorSpecs.count()) {
                MemoryUtil.memFree(pass.vulkanMetadata.descriptorSets)
                pass.vulkanMetadata.descriptorSets = MemoryUtil.memAllocLong(pipeline.descriptorSpecs.count())
            }

            // allocate more vertexBufferOffsets than needed, set limit lateron
            pass.vulkanMetadata.uboOffsets.position(0)
            pass.vulkanMetadata.uboOffsets.limit(16)
            (0..15).forEach { pass.vulkanMetadata.uboOffsets.put(it, 0) }

            if (logger.isDebugEnabled) {
                logger.debug("${pass.name}: descriptor sets are {}", pass.descriptorSets.keys.joinToString(", "))
                logger.debug("pipeline provides {}", pipeline.descriptorSpecs.keys.joinToString(", "))
            }

            // set the required descriptor sets for this render pass
            pass.vulkanMetadata.setRequiredDescriptorSetsPostprocess(pass, pipeline, sceneUBOs, descriptorSets)

            if(pipeline.pushConstantSpecs.containsKey("currentEye")) {
                VK10.vkCmdPushConstants(this, vulkanPipeline.layout, VK10.VK_SHADER_STAGE_ALL, 0, pass.vulkanMetadata.eye)
            }

            VK10.vkCmdBindPipeline(this, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, vulkanPipeline.pipeline)
            if(pass.vulkanMetadata.descriptorSets.limit() > 0) {
                logger.debug("Binding ${pass.vulkanMetadata.descriptorSets.limit()} descriptor sets with ${pass.vulkanMetadata.uboOffsets.limit()} required offsets")
                VK10.vkCmdBindDescriptorSets(this, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    vulkanPipeline.layout, 0, pass.vulkanMetadata.descriptorSets, pass.vulkanMetadata.uboOffsets)
            }

            VK10.vkCmdDraw(this, 3, 1, 0, 0)

            VK10.vkCmdEndRenderPass(this)

            commandBuffer.stale = false
            commandBuffer.endCommandBuffer()
        }
    }

    /**
     * Sets the necessary descriptor sets for a given [pass] with a [pipeline]. [sceneUBOs] and available
     * global [descriptorSets] need to be passed as well. Returns the number of required dynamic offsets.
     */
    fun VulkanRenderpass.VulkanMetadata.setRequiredDescriptorSetsPostprocess(pass: VulkanRenderpass, pipeline: VulkanPipeline, sceneUBOs: List<Node>, descriptorSets: Map<String, Long>): Int {
        var requiredDynamicOffsets = 0
        logger.trace("Ubo position: {}", this.uboOffsets.position())

        pipeline.descriptorSpecs.entries.sortedBy { it.value.set }.forEachIndexed { i, (name, spec) ->
            logger.trace("Looking at {}, set={}, binding={}...", name, spec.set, spec.binding)
            val dsName = when {
                name.startsWith("ShaderParameters") -> "ShaderParameters-${pass.name}"
                name.startsWith("Inputs") -> "input-${pass.name}-${spec.set}"
                name.startsWith("Matrices") -> {
                    val offsets = sceneUBOs.first().renderableOrNull()?.rendererMetadata()!!.UBOs["Matrices"]!!.second.offsets
                    this.uboOffsets.put(offsets)
                    requiredDynamicOffsets += 3

                    "Matrices"
                }
                else -> name
            }

            val set = if (dsName == "Matrices" || dsName == "LightParameters" || dsName == "VRParameters") {
                descriptorSets[dsName]
            } else {
                pass.descriptorSets[dsName]
            }

            if (set != null) {
                logger.debug("${pass.name}: Adding DS#{} for {} to required pipeline DSs ($set)", i, dsName, set)
                this.descriptorSets.put(i, set)
            } else {
                logger.error("DS for {} not found! Available from pass are: {}", dsName, pass.descriptorSets.keys().toList().joinToString(","))
            }
        }

        logger.trace("{}: Requires {} dynamic offsets", pass.name, requiredDynamicOffsets)
        this.uboOffsets.flip()

        return requiredDynamicOffsets
    }

}
