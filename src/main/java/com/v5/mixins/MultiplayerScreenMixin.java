package com.v5.mixins;

import com.v5.screen.ProxyManagerScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    @Unique
    private ButtonWidget v5_proxyButton;

    protected MultiplayerScreenMixin(Text text) {
        super(text);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void init(CallbackInfo ci) {
        this.v5_proxyButton = ButtonWidget.builder(Text.literal("V5 Proxies"), b -> {
                    if (this.client != null) {
                        this.client.setScreen(new ProxyManagerScreen(this));
                    }
                })
                .dimensions(0, 5, 80, 20)
                .build();

        this.addDrawableChild(this.v5_proxyButton);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.v5_proxyButton != null) {
            this.v5_proxyButton.setX(this.width - 80 - 5);
        }

        super.render(context, mouseX, mouseY, delta);
    }
}
