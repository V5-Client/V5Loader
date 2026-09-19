//? if >=26.3 {
/*package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.client.Client;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "extractPlayerState", at = @At("RETURN"))
    private void v5$useSpectatedHandAnimation(
            Camera camera,
            DeltaTracker tickCounter,
            float partialTick,
            PlayerRenderState state,
            CallbackInfo ci
    ) {
        LivingEntity target = Client.getSpectatedEntity();
        if (target == null || state.avatarRenderState == null) return;

        state.avatarRenderState.swingAnimation = target.getSwingAnimation(partialTick);
        LivingEntity.SwingDescription swing = target.getCurrentSwing();
        state.firstPersonHandsAndItems.attackHand = swing == null ? InteractionHand.MAIN_HAND : swing.hand();
    }
}
*///?}
