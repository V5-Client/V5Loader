package com.v5.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.v5.storage.V5MixinStorage;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
    @ModifyReturnValue(method = "getPlayerName", at = @At("RETURN"))
    private Text v5$getPlayerName(Text original) {
        return V5MixinStorage.applyMethod("nameProcessor", original, Text.class);
    }
}
