package com.chattriggers.ctjs.api.render

import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.PlayerEntityRenderer
import net.minecraft.client.render.entity.feature.*
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.model.EntityModelLayers
import net.minecraft.client.render.entity.model.EquipmentModelData
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack

internal class CTPlayerRenderer(
    private val ctx: EntityRendererFactory.Context,
    private val slim: Boolean,
) : PlayerEntityRenderer<AbstractClientPlayerEntity>(ctx, slim) {
    private val PLAYER_SLIM: EquipmentModelData<EntityModelLayers>
        @Suppress("UNCHECKED_CAST")
        get() = EntityModelLayers::class.java.getField("PLAYER_SLIM").get(null) as EquipmentModelData<EntityModelLayers>

    var showArmor = true
        set(value) {
            field = value
            reset()
        }
    var showHeldItem = true
        set(value) {
            field = value
            reset()
        }
    var showArrows = true
        set(value) {
            field = value
            reset()
        }
    var showCape = true
        set(value) {
            field = value
            reset()
        }
    var showElytra = true
        set(value) {
            field = value
            reset()
        }
    var showParrot = true
        set(value) {
            field = value
            reset()
        }
    var showStingers = true
        set(value) {
            field = value
            reset()
        }
    var showNametag = true
        set(value) {
            field = value
            reset()
        }

    fun setOptions(
        showNametag: Boolean = true,
        showArmor: Boolean = true,
        showCape: Boolean = true,
        showHeldItem: Boolean = true,
        showArrows: Boolean = true,
        showElytra: Boolean = true,
        showParrot: Boolean = true,
        showStingers: Boolean = true,
    ) {
        this.showNametag = showNametag
        this.showArmor = showArmor
        this.showCape = showCape
        this.showHeldItem = showHeldItem
        this.showArrows = showArrows
        this.showElytra = showElytra
        this.showParrot = showParrot
        this.showStingers = showStingers

        reset()
    }

    override fun renderLabelIfPresent(
        playerEntityRenderState: PlayerEntityRenderState?,
        matrixStack: MatrixStack?,
        orderedRenderCommandQueue: OrderedRenderCommandQueue?,
        cameraRenderState: CameraRenderState?
    ) {
        if (showNametag)
            super.renderLabelIfPresent(playerEntityRenderState, matrixStack, orderedRenderCommandQueue, cameraRenderState)
    }

    private fun reset() {
        features.clear()

        val entityModels = ctx.blockRenderManager.models.modelManager.entityModelsSupplier.get()

        if (showArmor) {
            val layer = if (slim) PLAYER_SLIM else EntityModelLayers.PLAYER_EQUIPMENT
            addFeature(
                ArmorFeatureRenderer(
                    this,
                    EquipmentModelData.mapToEntityModel(
                        layer as EquipmentModelData<EntityModelLayer>,
                        ctx.entityModels
                    ) { PlayerEntityModel(it, slim) },
                    ctx.equipmentRenderer
                )
            )
        }
        if (showHeldItem)
            addFeature(PlayerHeldItemFeatureRenderer(this))
        if (showArrows)
            addFeature(StuckArrowsFeatureRenderer(this, ctx))
        addFeature(Deadmau5FeatureRenderer(this, entityModels))
        if (showCape)
            addFeature(CapeFeatureRenderer(this, entityModels, ctx.equipmentModelLoader))
        if (showArmor)
            addFeature(HeadFeatureRenderer(this, entityModels, ctx.playerSkinCache))
        if (showElytra)
            addFeature(ElytraFeatureRenderer(this, entityModels, ctx.equipmentRenderer))
        if (showParrot)
            addFeature(ShoulderParrotFeatureRenderer(this, entityModels))
        addFeature(TridentRiptideFeatureRenderer(this, entityModels))
        if (showStingers)
            addFeature(StuckStingersFeatureRenderer(this, ctx))
    }
}
