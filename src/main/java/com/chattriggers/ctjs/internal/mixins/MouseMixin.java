package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.internal.engine.CTEvents;
import com.chattriggers.ctjs.internal.listeners.MouseListener;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.MouseInput;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow
    private MouseInput activeButton;

    @Inject(
        method = "onMouseButton",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;currentScreen:Lnet/minecraft/client/gui/screen/Screen;",
            opcode = Opcodes.GETFIELD
        )
    )
    private void injectOnMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        MouseListener.onRawMouseInput(input.button(), action);
    }

    @Inject(
        method = "onMouseScroll",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;options:Lnet/minecraft/client/option/GameOptions;",
            opcode = Opcodes.GETFIELD
        )
    )
    private void injectOnMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MouseListener.onRawMouseScroll(vertical);
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/Screen;mouseDragged(Lnet/minecraft/client/gui/Click;DD)Z"
        ),
        cancellable = true
    )
    private void injectOnGuiMouseDrag(
        CallbackInfo ci,
        @Local(ordinal = 0) double d,
        @Local(ordinal = 1) double e,
        @Local Screen screen,
        @Local(ordinal = 2) double f,
        @Local(ordinal = 3) double g)
    {
        if (screen != null) {
            CTEvents.GUI_MOUSE_DRAG.invoker().process(f, g, d, e, activeButton.button(), screen, ci);
        }
    }
}
