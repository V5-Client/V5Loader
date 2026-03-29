package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.world.Scoreboard;
import com.chattriggers.ctjs.internal.engine.CTEvents;
import gg.essential.universal.UMatrixStack;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void injectRenderScoreboard(DrawContext matrices, ScoreboardObjective objective, CallbackInfo ci) {
        if (!Scoreboard.getShouldRender())
            ci.cancel();
    }

    @Inject(
        method = "render",
        at = @At("TAIL"),
        require = 0
    )
    private void injectRenderOverlay(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        CTEvents.RENDER_OVERLAY.invoker().render(context, new UMatrixStack(context.getMatrices()).toMC(), tickCounter.getDynamicDeltaTicks());
    }
}
