package graphics.scenery.backends.vulkan

import graphics.scenery.*
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.RendererFlags
import graphics.scenery.backends.vulkan.VulkanNodeHelpers.rendererMetadata
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import kotlinx.coroutines.runBlocking
import org.joml.Vector3i
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import java.util.ArrayList

/**
 * Helper object for scene pass command buffer recording.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
object VulkanScenePass {
    val logger by LazyLogger()

    /**
     * Records a new scene command buffer.
     * Needs a [hub], the [pass] to be recorded, the [commandBuffer] it should write to,
     * [commandPools] to allocate further buffers from, the [renderConfig] as well as available [renderpasses],
     * the list of objects in the scene, [sceneObjects]. A custom filter for nodes can be given in [customNodeFilter], and recording can be forced by setting [forceRerecording] to true.
     */
    fun record(
        hub: Hub,
        pass: VulkanRenderpass,
        commandBuffer: VulkanCommandBuffer,
        commandPools: VulkanRenderer.CommandPools,
        descriptorSets: Map<String, Long>,
        renderConfig: RenderConfigReader.RenderConfig,
        renderpasses: Map<String, VulkanRenderpass>,
        sceneObjects: List<Node>,
        customNodeFilter: ((Node) -> Boolean)? = null,
        forceRerecording: Boolean = false
    ) = runBlocking {
        val target = pass.getOutput()

        logger.trace("Initialising recording of scene command buffer for {}/{} ({} attachments)", pass.name, target, target.attachments.count())

        pass.vulkanMetadata.renderPassBeginInfo
            .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
            .pNext(MemoryUtil.NULL)
            .renderPass(target.renderPass.get(0))
            .framebuffer(target.framebuffer.get(0))
            .renderArea(pass.vulkanMetadata.renderArea)
            .pClearValues(pass.vulkanMetadata.clearValues)

        val renderOrderList = ArrayList<Node>(pass.vulkanMetadata.renderLists[commandBuffer]?.size ?: 512)

        var needsOrderSort = false

        // here we discover all the nodes which are relevant for this pass,
        // e.g. which have the same transparency settings as the pass,
        // and filter according to any custom filters applicable to this pass
        // (e.g. to discern geometry from lighting passes)
        val seenDelegates = ArrayList<Node>(5)
        sceneObjects.filter { customNodeFilter?.invoke(it) ?: true }.forEach { node ->
            val n = if(node is DelegatesRendering) {
                val delegate = node.delegate
                if(node.delegationType == DelegationType.OncePerDelegate && delegate != null) {
                    if(delegate in seenDelegates) {
                        return@forEach
                    } else {
                        seenDelegates.add(delegate)
                        delegate
                    }
                } else {
                    node.delegate ?: return@forEach
                }
            } else {
                node
            }

            if(n.state != State.Ready || n.rendererMetadata()?.preDrawSkip == true) {
                return@forEach
            }

            if(n is RenderingOrder) {
                needsOrderSort = true
            }

            n.rendererMetadata()?.let {
                if (!((pass.passConfig.renderOpaque && n.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) ||
                        (pass.passConfig.renderTransparent && !n.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent))) {
                    renderOrderList.add(n)
                } else {
                    return@let
                }
            }
        }

        if(needsOrderSort) {
            renderOrderList.sortBy { (it as? RenderingOrder)?.renderingOrder }
        }
        // if the pass' metadata does not contain a command buffer,
        // OR the cached command buffer does not contain the same nodes in the same order,
        // OR re-recording is forced due to node changes, the buffer will be re-recorded.
        // Furthermore, all sibling command buffers for this pass will be marked stale, thus
        // also forcing their re-recording.
        if(!pass.vulkanMetadata.renderLists.containsKey(commandBuffer)
            || !renderOrderList.toTypedArray().contentDeepEquals(pass.vulkanMetadata.renderLists.getValue(commandBuffer))
            || forceRerecording) {

            pass.vulkanMetadata.renderLists[commandBuffer] = renderOrderList.toTypedArray()
            pass.invalidateCommandBuffers()

            // if we are in a VR pass, invalidate passes for both eyes to prevent one of them housing stale data
            if(renderConfig.stereoEnabled && (pass.name.contains("Left") || pass.name.contains("Right"))) {
                val passLeft = if(pass.name.contains("Left")) {
                    pass.name
                } else {
                    pass.name.substringBefore("Right") + "Left"
                }

                val passRight = if(pass.name.contains("Right")) {
                    pass.name
                } else {
                    pass.name.substringBefore("Left") + "Right"
                }

                renderpasses[passLeft]?.invalidateCommandBuffers()
                renderpasses[passRight]?.invalidateCommandBuffers()
            }
        }

        // If the command buffer is not stale, though, we keep the cached one and return. This
        // can buy quite a bit of performance.
        if(!commandBuffer.stale && commandBuffer.commandBuffer != null) {
            return@runBlocking
        }

        logger.debug("Recording scene command buffer $commandBuffer for pass ${pass.name}...")

        var drawCalls = 0
        var pipelineSwitches = 0

        // command buffer cannot be null here anymore, otherwise this is clearly in error
        with(commandBuffer.prepareAndStartRecording(commandPools.Render)) {
            if(pass.passConfig.blitInputs) {
                for ((name, input) in pass.inputs) {
                    this.blitInputsForPass(pass, name, input)
                }
            }

            val computeNodesGraphicsNodes = renderOrderList.partition { pass.getActivePipeline(it).type == VulkanPipeline.PipelineType.Compute }

            computeNodesGraphicsNodes.first.forEach computeLoop@ { node ->
                val s = node.rendererMetadata() ?: return@computeLoop

                val metadata = node.metadata["ComputeMetadata"] as? ComputeMetadata ?: ComputeMetadata(Vector3i(pass.getOutput().width, pass.getOutput().height, 1))

                val pipeline = pass.getActivePipeline(node)
                val vulkanPipeline = pipeline.getPipelineForGeometryType(GeometryType.TRIANGLES)

                if (pass.vulkanMetadata.descriptorSets.capacity() != pipeline.descriptorSpecs.count()) {
                    MemoryUtil.memFree(pass.vulkanMetadata.descriptorSets)
                    pass.vulkanMetadata.descriptorSets = MemoryUtil.memAllocLong(pipeline.descriptorSpecs.count())
                }

                val specs = pipeline.orderedDescriptorSpecs()
                val (sets, skip) = setRequiredDescriptorSetsForNode(pass, node, s, specs, descriptorSets)

                if(skip || !metadata.active) {
                    return@computeLoop
                }

                val requiredSets = sets.filter { it !is VulkanRenderer.DescriptorSet.None }.map { it.id }.toLongArray()
                if(pass.vulkanMetadata.descriptorSets.capacity() < requiredSets.size) {
                    logger.debug("Reallocating descriptor set storage")
                    MemoryUtil.memFree(pass.vulkanMetadata.descriptorSets)
                    pass.vulkanMetadata.descriptorSets = MemoryUtil.memAllocLong(requiredSets.size)
                }

                pass.vulkanMetadata.descriptorSets.position(0)
                pass.vulkanMetadata.descriptorSets.limit(pass.vulkanMetadata.descriptorSets.capacity())
                pass.vulkanMetadata.descriptorSets.put(requiredSets)
                pass.vulkanMetadata.descriptorSets.flip()

                pass.vulkanMetadata.uboOffsets.position(0)
                pass.vulkanMetadata.uboOffsets.limit(pass.vulkanMetadata.uboOffsets.capacity())
                pass.vulkanMetadata.uboOffsets.put(sets.filterIsInstance<VulkanRenderer.DescriptorSet.DynamicSet>().map { it.offset }.toIntArray())
                pass.vulkanMetadata.uboOffsets.flip()

                // allocate more vertexBufferOffsets than needed, set limit lateron
//                pass.vulkanMetadata.uboOffsets.position(0)
//                pass.vulkanMetadata.uboOffsets.limit(16)
//                (0..15).forEach { pass.vulkanMetadata.uboOffsets.put(it, 0) }

                val loadStoreTextures =
                    node.material.textures
                        .filter { it.value.usageType.contains(Texture.UsageType.LoadStoreImage)}

                val localSizes = pipeline.shaderStages.first().localSize

                loadStoreTextures
                    .forEach { (name, _) ->
                        val texture = s.textures[name] ?: return@computeLoop
                        VulkanTexture.transitionLayout(texture.image.image,
                            from = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            to = VK10.VK_IMAGE_LAYOUT_GENERAL,
                            srcStage = VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                            srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT,
                            dstStage = VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            dstAccessMask = VK10.VK_ACCESS_SHADER_WRITE_BIT,
                            commandBuffer = this)

                    }

                VK10.vkCmdBindPipeline(this, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, vulkanPipeline.pipeline)

                if(pipeline.pushConstantSpecs.containsKey("currentEye")) {
                    VK10.vkCmdPushConstants(this, vulkanPipeline.layout, VK10.VK_SHADER_STAGE_ALL, 0, pass.vulkanMetadata.eye)
                }

                if(pass.vulkanMetadata.descriptorSets.limit() > 0) {
                    logger.debug("${pass.name}: Binding ${pass.vulkanMetadata.descriptorSets.limit()} descriptor sets with ${pass.vulkanMetadata.uboOffsets.limit()} required offsets")
                    VK10.vkCmdBindDescriptorSets(this, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                        vulkanPipeline.layout, 0, pass.vulkanMetadata.descriptorSets, pass.vulkanMetadata.uboOffsets)
                }

                val maxGroupCount = intArrayOf(1, 1, 1)
                commandBuffer.device.deviceData.properties.limits().maxComputeWorkGroupCount().get(maxGroupCount)

                val groupCount = intArrayOf(
                    metadata.workSizes.x()/localSizes.first,
                    metadata.workSizes.y()/localSizes.second,
                    metadata.workSizes.z()/localSizes.third)

                groupCount.forEachIndexed { i, gc ->
                    if(gc > maxGroupCount[i]) {
                        logger.warn("Group count {} exceeds device maximum of {}, using device maximum.", gc, maxGroupCount[i])
                        groupCount[i] = maxGroupCount[i]
                    }
                }

                VK10.vkCmdDispatch(this, groupCount[0], groupCount[1], groupCount[2])
                drawCalls++

                loadStoreTextures.forEach { (name, _) ->
                    val texture = s.textures[name] ?: return@computeLoop
                    VulkanTexture.transitionLayout(texture.image.image,
                        from = VK10.VK_IMAGE_LAYOUT_GENERAL,
                        to = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        srcStage = VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        srcAccessMask = VK10.VK_ACCESS_SHADER_WRITE_BIT,
                        dstStage = VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                        dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT,
                        commandBuffer = this)

                }

                if(metadata.invocationType == InvocationType.Triggered && metadata.active) {
                    metadata.active = false
                }

                if(metadata.invocationType == InvocationType.Once) {
                    metadata.active = false
                }
            }

            VK10.vkCmdBeginRenderPass(this, pass.vulkanMetadata.renderPassBeginInfo, VK10.VK_SUBPASS_CONTENTS_INLINE)

            VK10.vkCmdSetViewport(this, 0, pass.vulkanMetadata.viewport)
            VK10.vkCmdSetScissor(this, 0, pass.vulkanMetadata.scissor)

            // allocate more vertexBufferOffsets than needed, set limit lateron
            pass.vulkanMetadata.uboOffsets.limit(16)
            (0 until pass.vulkanMetadata.uboOffsets.limit()).forEach { pass.vulkanMetadata.uboOffsets.put(it, 0) }

            var previousPipeline: VulkanRenderer.Pipeline? = null
            computeNodesGraphicsNodes.second.forEach drawLoop@ { node ->
                val s = node.rendererMetadata() ?: return@drawLoop

                // nodes that just have been initialised will also be skipped
                if(!s.flags.contains(RendererFlags.Updated)) {
                    return@drawLoop
                }

                // instanced nodes will not be drawn directly, but only the master node.
                // nodes with no vertices will also not be drawn.
                if(s.vertexCount == 0) {
                    return@drawLoop
                }

                // return if we are on a opaque pass, but the node requires transparency.
                if(pass.passConfig.renderOpaque && node.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                    return@drawLoop
                }

                // return if we are on a transparency pass, but the node is only opaque.
                if(pass.passConfig.renderTransparent && !node.material.blending.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                    return@drawLoop
                }

                val vertexIndexBuffer = s.vertexBuffers["vertex+index"]
                val instanceBuffer = s.vertexBuffers["instance"]

                if(vertexIndexBuffer == null) {
                    logger.error("Vertex+Index buffer not initialiazed")
                    return@drawLoop
                }

                logger.trace("{} - Rendering {}, vertex+index buffer={}...", pass.name, node.name, vertexIndexBuffer.vulkanBuffer.toHexString())
//                if(rerecordingCauses.contains(node.name)) {
//                    logger.debug("Using pipeline ${pass.getActivePipeline(node)} for re-recording")
//                }
                val p = pass.getActivePipeline(node)
                val pipeline = p.getPipelineForGeometryType((node as HasGeometry).geometryType)
                val specs = p.orderedDescriptorSpecs()

                if(pipeline != previousPipeline) {
                    VK10.vkCmdBindPipeline(this, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)
                    previousPipeline = pipeline
                    pipelineSwitches++
                }

                if(logger.isTraceEnabled) {
                    logger.trace("node {} has: {} / pipeline needs: {}", node.name, s.UBOs.keys.joinToString(", "), specs.joinToString { it.key })
                }

                pass.vulkanMetadata.descriptorSets.rewind()
                pass.vulkanMetadata.uboOffsets.rewind()

                pass.vulkanMetadata.vertexBufferOffsets.put(0, vertexIndexBuffer.bufferOffset)
                pass.vulkanMetadata.vertexBuffers.put(0, vertexIndexBuffer.vulkanBuffer)

                pass.vulkanMetadata.vertexBufferOffsets.limit(1)
                pass.vulkanMetadata.vertexBuffers.limit(1)

                if(node.instancedProperties.size > 0) {
                    if (node.instances.size > 0 && instanceBuffer != null) {
                        pass.vulkanMetadata.vertexBuffers.limit(2)
                        pass.vulkanMetadata.vertexBufferOffsets.limit(2)

                        pass.vulkanMetadata.vertexBufferOffsets.put(1, 0)
                        pass.vulkanMetadata.vertexBuffers.put(1, instanceBuffer.vulkanBuffer)
                    } else {
                        return@drawLoop
                    }
                }

                val (sets, skip) = setRequiredDescriptorSetsForNode(pass, node, s, specs, descriptorSets)

                if(skip) {
                    return@drawLoop
                }

                if(logger.isDebugEnabled) {
                    logger.debug("${node.name} requires DS ${specs.joinToString { "${it.key}, " }}")
                }

                val requiredSets = sets.filter { it !is VulkanRenderer.DescriptorSet.None }.map { it.id }.toLongArray()
                if (pass.vulkanMetadata.descriptorSets.capacity() < requiredSets.size) {
                    logger.debug("Reallocating descriptor set storage")
                    MemoryUtil.memFree(pass.vulkanMetadata.descriptorSets)
                    pass.vulkanMetadata.descriptorSets = MemoryUtil.memAllocLong(requiredSets.size)
                }

                pass.vulkanMetadata.descriptorSets.position(0)
                pass.vulkanMetadata.descriptorSets.limit(pass.vulkanMetadata.descriptorSets.capacity())
                pass.vulkanMetadata.descriptorSets.put(requiredSets)
                pass.vulkanMetadata.descriptorSets.flip()

                pass.vulkanMetadata.uboOffsets.position(0)
                pass.vulkanMetadata.uboOffsets.limit(pass.vulkanMetadata.uboOffsets.capacity())
                pass.vulkanMetadata.uboOffsets.put(sets.filter { it is VulkanRenderer.DescriptorSet.DynamicSet }.map { (it as VulkanRenderer.DescriptorSet.DynamicSet).offset }.toIntArray())
                pass.vulkanMetadata.uboOffsets.flip()

                if(pass.vulkanMetadata.descriptorSets.limit() > 0) {
                    VK10.vkCmdBindDescriptorSets(this, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.layout, 0, pass.vulkanMetadata.descriptorSets, pass.vulkanMetadata.uboOffsets)
                }


                if(p.pushConstantSpecs.containsKey("currentEye")) {
                    VK10.vkCmdPushConstants(this, pipeline.layout, VK10.VK_SHADER_STAGE_ALL, 0, pass.vulkanMetadata.eye)
                }

                VK10.vkCmdBindVertexBuffers(this, 0, pass.vulkanMetadata.vertexBuffers, pass.vulkanMetadata.vertexBufferOffsets)

                logger.debug("${pass.name}: now drawing {}, {} DS bound, {} textures, {} vertices, {} indices, {} instances", node.name, pass.vulkanMetadata.descriptorSets.limit(), s.textures.count(), s.vertexCount, s.indexCount, s.instanceCount)

                if (s.isIndexed) {
                    VK10.vkCmdBindIndexBuffer(this, pass.vulkanMetadata.vertexBuffers.get(0), s.indexOffset, VK10.VK_INDEX_TYPE_UINT32)

                    if(node is Mesh && node.splitDrawCalls.isNotEmpty()) {
                        val rect = VkRect2D.calloc(1)
                        node.splitDrawCalls.forEach { state ->
                            state.scissor?.let { scissor ->
                                rect.offset().set(scissor.offset.x, scissor.offset.y)
                                rect.extent().set(scissor.extent.x, scissor.extent.y)

                                VK10.vkCmdSetScissor(
                                    this,
                                    0,
                                    rect
                                )
                            }

                            VK10.vkCmdDrawIndexed(
                                this,
                                state.count,
                                s.instanceCount,
                                state.indexOffset,
                                state.vertexOffset,
                                0
                            )
                        }

                        rect.free()
                    } else {
                        VK10.vkCmdDrawIndexed(
                            this,
                            s.indexCount,
                            s.instanceCount,
                            0,
                            0,
                            0
                        )
                    }
                } else {
                    VK10.vkCmdDraw(this, s.vertexCount, s.instanceCount, 0, 0)
                }
                drawCalls++
            }

            VK10.vkCmdEndRenderPass(this)

            hub.get<Statistics>()?.let {
                it.add("Renderer.${pass.name}.DrawCalls", drawCalls, false)
                it.add("Renderer.${pass.name}.PipelineSwitches", pipelineSwitches, false)
            }

            // finish command buffer recording by marking this buffer non-stale
            commandBuffer.stale = false
            commandBuffer.endCommandBuffer()
        }
    }

    /**
     * Blits [input] with [name] into the current [pass]' framebuffer.
     */
    private fun VkCommandBuffer.blitInputsForPass(pass: VulkanRenderpass, name: String, input: VulkanFramebuffer) {
        MemoryStack.stackPush().use { stack ->
            val imageBlit = VkImageBlit.callocStack(1, stack)
            val region = VkImageCopy.callocStack(1, stack)

            val attachmentList = if (name.contains(".")) {
                input.attachments.filter { it.key == name.substringAfter(".") }
            } else {
                input.attachments
            }

            for ((_, inputAttachment) in attachmentList) {
                val type = when (inputAttachment.type) {
                    VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK10.VK_IMAGE_ASPECT_COLOR_BIT
                    VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK10.VK_IMAGE_ASPECT_DEPTH_BIT
                }

                // return to use() if no output with the correct attachment type is found
                val outputAttachment = pass.getOutput().attachments.values.find { it.type == inputAttachment.type }
                if (outputAttachment == null) {
                    logger.warn("Didn't find matching attachment for $name of type ${inputAttachment.type}")
                    return@use
                }

                val outputAspectSrcType = when (outputAttachment.type) {
                    VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                }

                val outputAspectDstType = when (outputAttachment.type) {
                    VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                    VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                }

                val inputAspectType = when (inputAttachment.type) {
                    VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT -> VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT -> VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                }

                val (outputDstStage, outputDstAccessMask) = when (outputAttachment.type) {
                    VulkanFramebuffer.VulkanFramebufferType.COLOR_ATTACHMENT ->
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT to VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                    VulkanFramebuffer.VulkanFramebufferType.DEPTH_ATTACHMENT ->
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT or VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT or VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT to VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                }

                val offsetX = (input.width * pass.passConfig.viewportOffset.first).toInt()
                val offsetY = (input.height * pass.passConfig.viewportOffset.second).toInt()

                val sizeX = (input.width * pass.passConfig.viewportSize.first).toInt()
                val sizeY = (input.height * pass.passConfig.viewportSize.second).toInt()

                imageBlit.srcSubresource().set(type, 0, 0, 1)
                imageBlit.srcOffsets(0).set(offsetX, offsetY, 0)
                imageBlit.srcOffsets(1).set(sizeX, sizeY, 1)

                imageBlit.dstSubresource().set(type, 0, 0, 1)
                imageBlit.dstOffsets(0).set(offsetX, offsetY, 0)
                imageBlit.dstOffsets(1).set(sizeX, sizeY, 1)

                val subresourceRange = VkImageSubresourceRange.callocStack(stack)
                    .aspectMask(type)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                // transition source attachment
                VulkanTexture.transitionLayout(inputAttachment.image,
                    from = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    to = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    srcAccessMask = 0,
                    dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstAccessMask = VK10.VK_ACCESS_TRANSFER_READ_BIT or VK10.VK_ACCESS_MEMORY_READ_BIT,
                    subresourceRange = subresourceRange,
                    commandBuffer = this
                )

                // transition destination attachment
                VulkanTexture.transitionLayout(outputAttachment.image,
                    from = inputAspectType,
                    to = VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    srcAccessMask = 0,
                    dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    subresourceRange = subresourceRange,
                    commandBuffer = this
                )

                if (inputAttachment.compatibleWith(input, outputAttachment, pass.getOutput())) {
                    logger.debug("Using vkCmdCopyImage instead of blit because of compatible framebuffers between {} and {}", name, pass.name)
                    region.srcOffset().set(offsetX, offsetY, 0)
                    region.dstOffset().set(offsetX, offsetY, 0)
                    region.extent().set(sizeX, sizeY, 1)
                    region.srcSubresource().set(type, 0, 0, 1)
                    region.dstSubresource().set(type, 0, 0, 1)

                    VK10.vkCmdCopyImage(this,
                        inputAttachment.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        outputAttachment.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region
                    )
                } else {
                    VK10.vkCmdBlitImage(this,
                        inputAttachment.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        outputAttachment.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        imageBlit, VK10.VK_FILTER_NEAREST
                    )
                }


                // transition destination attachment back to attachment
                VulkanTexture.transitionLayout(
                    outputAttachment.image,
                    from = VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    to = outputAspectDstType,
                    srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    dstStage = outputDstStage,
                    dstAccessMask = outputDstAccessMask,
                    subresourceRange = subresourceRange,
                    commandBuffer = this,
                )

                // transition source attachment back to shader read-only
                VulkanTexture.transitionLayout(
                    inputAttachment.image,
                    from = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    to = outputAspectSrcType,
                    srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstStage = VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    srcAccessMask = 0,
                    dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT,
                    subresourceRange = subresourceRange,
                    commandBuffer = this,
                )
            }
        }
    }

    private fun setRequiredDescriptorSetsForNode(pass: VulkanRenderpass, node: Node, s: VulkanObjectState, specs: List<MutableMap.MutableEntry<String, VulkanShaderModule.UBOSpec>>, descriptorSets: Map<String, Long>): Pair<List<VulkanRenderer.DescriptorSet>, Boolean> {
        var skip = false
        return specs.mapNotNull { (name, _) ->
            val ds = when {
                name == "VRParameters" -> {
                    VulkanRenderer.DescriptorSet.setOrNull(descriptorSets["VRParameters"], setName = "VRParameters")
                }

                name == "LightParameters" -> {
                    VulkanRenderer.DescriptorSet.setOrNull(descriptorSets["LightParameters"], setName = "LightParameters")
                }

                name.startsWith("Inputs") -> {
                    VulkanRenderer.DescriptorSet.setOrNull(pass.descriptorSets["input-${pass.name}-${name.substringAfter("-")}"], setName = "Inputs")
                }

                name == "ShaderParameters" -> {
                    VulkanRenderer.DescriptorSet.setOrNull(pass.descriptorSets["ShaderParameters-${pass.name}"], setName = "ShaderParameters")
                }

                else -> {
                    when {
                        s.UBOs.containsKey(name) ->
                            VulkanRenderer.DescriptorSet.DynamicSet(s.UBOs.getValue(name).first, offset = s.UBOs.getValue(name).second.offsets.get(0), setName = name)
                        s.UBOs.containsKey("${pass.name}-$name") ->
                            VulkanRenderer.DescriptorSet.DynamicSet(s.UBOs.getValue("${pass.name}-$name").first, offset = s.UBOs.getValue("${pass.name}-$name").second.offsets.get(0), setName = name)
                        s.getTextureDescriptorSet(pass.passConfig.type.name, name) != null ->
                            VulkanRenderer.DescriptorSet.setOrNull(s.getTextureDescriptorSet(pass.passConfig.type.name, name), name)
                        else -> VulkanRenderer.DescriptorSet.None
                    }
                }
            }

            if(ds == null || ds == VulkanRenderer.DescriptorSet.None) {
                logger.error("Internal consistency error for node ${node.name}: Descriptor set $name not found in renderpass ${pass.name}, skipping node for rendering.")
                skip = true
            }

            if(ds is VulkanRenderer.DescriptorSet.DynamicSet && ds.offset == BUFFER_OFFSET_UNINTIALISED ) {
                logger.info("${node.name} has uninitialised UBO offset, skipping for rendering")
                skip = true
            }

            ds
        }.distinctBy { it.id } to skip
    }
}
