package com.v5.mixins;

import com.v5.qol.Xray;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractTerrainRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.block.Block;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StainedGlassPaneBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("UnstableApiUsage")
@Mixin(AbstractTerrainRenderContext.class)
public abstract class AbstractTerrainRenderContextMixin {
	@Final
	@Shadow(remap = false)
	protected BlockRenderInfo blockInfo;

	@Inject(method = "bufferQuad", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AbstractTerrainRenderContext;bufferQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;Lnet/minecraft/client/render/VertexConsumer;)V"), cancellable = true)
	private void cancelRenders(MutableQuadViewImpl quad, CallbackInfo ci) {
		if (Xray.isEnabled) {
			Block block = blockInfo.blockState.getBlock();
			int alpha = Xray.returnAlpha(blockInfo.blockPos, blockInfo.blockState);

			if (block instanceof StainedGlassPaneBlock || block instanceof StainedGlassBlock) {
				// do nothing / render
			} else {
				if (alpha == 0) ci.cancel();
				else if (alpha != -1) {
					for (int i = 0; i < 4; i++) {
						quad.color(i, setAlpha(quad.color(i), alpha));
					}
				}
			}
		}
	}

	@Unique
	private int setAlpha(int color, int alpha) {
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}
}

