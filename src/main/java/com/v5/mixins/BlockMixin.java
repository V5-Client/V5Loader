package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import java.util.Set;
import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public class BlockMixin {
    private static final Set<String> TARGET_KEYS =
            Set.of(
                    "block.minecraft.melon",
                    "block.minecraft.pumpkin",
                    "block.minecraft.carrots",
                    "block.minecraft.potatoes",
                    "block.minecraft.wheat",
                    "block.minecraft.nether_wart",
                    "block.minecraft.sugar_cane",
                    "block.minecraft.cactus",
                    "block.minecraft.cocoa",
                    "block.minecraft.melon_stem",
                    "block.minecraft.pumpkin_stem",
                    "block.minecraft.carved_pumpkin");

    @Inject(method = "spawnBreakParticles", at = @At("HEAD"), cancellable = true)
    private void v5$spawnBreakParticles(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("hideParticles", false)) {
            return;
        }

        String blockKey = ((Block) (Object) this).getTranslationKey();
        if (TARGET_KEYS.contains(blockKey)) {
            ci.cancel();
        }
    }
}
