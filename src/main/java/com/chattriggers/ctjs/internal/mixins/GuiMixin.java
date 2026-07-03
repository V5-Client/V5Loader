package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.world.Scoreboard;
import com.chattriggers.ctjs.internal.engine.CTEvents;
import gg.essential.universal.UMatrixStack;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void injectRenderScoreboard(GuiGraphicsExtractor matrices, DeltaTracker tickCounter, CallbackInfo ci) {
        if (!Scoreboard.getShouldRender())
            ci.cancel();
    }

    @Inject(
        method = "extractRenderState",
        at = @At("TAIL")
    )
    private void injectRenderOverlay(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        CTEvents.RENDER_OVERLAY.invoker().render(context, new UMatrixStack(context.pose()).toMC(), tickCounter.getGameTimeDeltaTicks());
    }
}
