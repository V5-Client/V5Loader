package com.v5.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.v5.storage.V5MixinStorage;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component v5$getPlayerName(Component original) {
        return V5MixinStorage.applyMethod("nameProcessor", original, Component.class);
    }
}
