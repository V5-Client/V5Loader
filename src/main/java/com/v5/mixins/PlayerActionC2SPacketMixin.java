package com.v5.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.v5.storage.V5MixinStorage;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerActionC2SPacket.class)
public class PlayerActionC2SPacketMixin {
    @ModifyReturnValue(method = "getSequence", at = @At("RETURN"))
    private int v5$getSequence(int sequence) {
        V5MixinStorage.set("playerActionSequence", sequence);
        return sequence;
    }
}
