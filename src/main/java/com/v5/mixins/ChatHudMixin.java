package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public class ChatHudMixin {
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;)V",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true)
    private Text v5$addMessage(Text original) {
        return V5MixinStorage.applyMethod("nameProcessor", original, Text.class);
    }
}
