package com.chattriggers.ctjs.api.render

import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.ArmorModelSet
import net.minecraft.client.renderer.entity.layers.ArrowLayer
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer
import net.minecraft.client.renderer.entity.layers.CapeLayer
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer
import net.minecraft.client.renderer.entity.layers.SpinAttackEffectLayer
import net.minecraft.client.renderer.entity.layers.WingsLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import com.mojang.blaze3d.vertex.PoseStack

internal class CTPlayerRenderer(
    private val ctx: EntityRendererProvider.Context,
    private val slim: Boolean,
) : AvatarRenderer<AbstractClientPlayer>(ctx, slim) {
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

    override fun submitNameDisplay(
        playerEntityRenderState: AvatarRenderState,
        matrixStack: PoseStack,
        orderedRenderCommandQueue: SubmitNodeCollector,
        cameraRenderState: CameraRenderState
    ) {
        if (showNametag)
            super.submitNameDisplay(playerEntityRenderState, matrixStack, orderedRenderCommandQueue, cameraRenderState)
    }

    private fun reset() {
        layers.clear()

        val entityModels = ctx.modelSet

        if (showArmor) {
            val layer = if (slim) ModelLayers.PLAYER_SLIM_ARMOR else ModelLayers.PLAYER_ARMOR
            addLayer(
                HumanoidArmorLayer(
                    this,
                    ArmorModelSet.bake(
                        layer,
                        ctx.modelSet
                    ) { PlayerModel(it, slim) },
                    ctx.equipmentRenderer
                )
            )
        }
        if (showHeldItem)
            addLayer(PlayerItemInHandLayer(this))
        if (showArrows)
            addLayer(ArrowLayer(this, ctx))
        addLayer(Deadmau5EarsLayer(this, entityModels))
        if (showCape)
            addLayer(CapeLayer(this, entityModels, ctx.equipmentAssets))
        if (showArmor)
            addLayer(CustomHeadLayer(this, entityModels, ctx.playerSkinRenderCache))
        if (showElytra)
            addLayer(WingsLayer(this, entityModels, ctx.equipmentRenderer))
        if (showParrot)
            addLayer(ParrotOnShoulderLayer(this, entityModels))
        addLayer(SpinAttackEffectLayer(this, entityModels))
        if (showStingers)
            addLayer(BeeStingerLayer(this, ctx))
    }
}
