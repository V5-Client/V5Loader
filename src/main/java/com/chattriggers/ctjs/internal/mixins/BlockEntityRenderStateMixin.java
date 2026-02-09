package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.api.client.Client;
import com.chattriggers.ctjs.internal.engine.CTEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderState.class)
public class BlockEntityRenderStateMixin {

    @Unique
    private static final MatrixStack stack = new MatrixStack();

    @Inject(
        method = "updateBlockEntityRenderState(Lnet/minecraft/block/entity/BlockEntity;Lnet/minecraft/client/render/block/entity/state/BlockEntityRenderState;Lnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V",
        at = @At("HEAD")
    )
    private static void onUpdate(BlockEntity blockEntity, BlockEntityRenderState state, ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay, CallbackInfo ci) {
        if (blockEntity.getWorld() != null && blockEntity.getWorld().isClient()) {

            stack.push();

            CTEvents.RENDER_BLOCK_ENTITY.invoker().render(
                stack,
                blockEntity,
                Client.getMinecraft().getRenderTickCounter().getDynamicDeltaTicks(),
                ci
            );

            stack.pop();
        }
    }
}
