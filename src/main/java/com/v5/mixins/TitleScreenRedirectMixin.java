package com.v5.mixins;

import com.v5.screen.V5MainMenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenRedirectMixin {

    @Unique
    private boolean v5$didRedirect;

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void v5$redirectToCustomMenu(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!v5$didRedirect && client != null && !(client.currentScreen instanceof V5MainMenuScreen)) {
            v5$didRedirect = true;
            client.setScreen(new V5MainMenuScreen());
        }
        ci.cancel();
    }
}
