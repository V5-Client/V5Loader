package com.v5.mixins;

import com.v5.qol.Xray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.util.math.BlockPos;


@Mixin(ChunkOcclusionDataBuilder.class)
public class ChunkOcclusionDataBuilderMixin {
	@Inject(at = { @At("HEAD") }, method = { "markClosed" }, cancellable = true)
	private void onMarkClosed(BlockPos pos, CallbackInfo ci) {
		if (Xray.isEnabled) { // stops blocks behind rendered blocks from being unrendered
			ci.cancel();
		}
	}
}
