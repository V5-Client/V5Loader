package com.v5.mixins;

import com.v5.qol.Xray;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.List;

@Mixin(BlockModelRenderer.class)
public class BlockModelRendererMixin {
    @Unique
    private final ThreadLocal<Integer> alphas = ThreadLocal.withInitial(() -> -1);

    @Inject(method = {"renderSmooth", "renderFlat"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void onRenderSmooth(BlockRenderView world, List<BlockModelPart> parts, BlockState state, BlockPos pos, MatrixStack matrices, VertexConsumer vertexConsumer, boolean cull, int overlay, CallbackInfo ci) {
        if (Xray.isEnabled) {
            int alpha = Xray.alpha;

            if (alpha == 0) ci.cancel();
            else alphas.set(alpha);
        }
    }

    @ModifyArgs(method = "renderQuad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumer;quad(Lnet/minecraft/client/util/math/MatrixStack$Entry;Lnet/minecraft/client/render/model/BakedQuad;[FFFFF[IIZ)V"), require = 0)
    private void modifyXrayAlphaLegacy(final Args args) {
        applyXrayAlpha(args, 6);
    }

    @ModifyArgs(method = "renderQuad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumer;quad(Lnet/minecraft/client/util/math/MatrixStack$Entry;Lnet/minecraft/client/render/model/BakedQuad;FFFFII)V"), require = 0)
    private void modifyXrayAlphaModernQuad(final Args args) {
        applyXrayAlpha(args, 5);
    }

    @ModifyArgs(method = "renderQuad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumer;putBulkData(Lnet/minecraft/client/util/math/MatrixStack$Entry;Lnet/minecraft/client/render/model/BakedQuad;FFFFII)V"), require = 0)
    private void modifyXrayAlphaModernBulkData(final Args args) {
        applyXrayAlpha(args, 5);
    }

    @Unique
    private void applyXrayAlpha(final Args args, final int alphaIndex) {
        final Integer alpha = alphas.get();
        if (alpha == null || alpha == -1) return;
        args.set(alphaIndex, alpha / 255f);
    }
}
