package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void v5$cancelMovementWhileFreecam(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("freecamEnabled", false)) {
            return;
        }

        this.keyPresses = Input.EMPTY;
        this.moveVector = Vec2.ZERO;
    }
}
