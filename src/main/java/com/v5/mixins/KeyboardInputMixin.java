package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void v5$cancelMovementWhileFreecam(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("freecamEnabled", false)) {
            return;
        }

        this.playerInput = PlayerInput.DEFAULT;
        this.movementVector = Vec2f.ZERO;
    }
}
