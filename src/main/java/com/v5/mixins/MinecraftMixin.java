package com.v5.mixins;

import com.chattriggers.ctjs.api.client.Client;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.sounds.SoundSource;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow @Final public Options options;

    private Integer v5$savedDistance;
    private CameraType v5$savedPerspective;
    private Integer v5$savedFps;
    private Float v5$savedMasterVolume;

    @ModifyExpressionValue(
            method = "pauseIfInactive",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z",
                    opcode = Opcodes.GETFIELD
            ))
    private boolean v5$pauseIfInactive(boolean original) {
        return false;
    }

    @ModifyExpressionValue(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"
            )
    )
    private boolean v5$allowAttackWhileUngrabbed(boolean original) {
        return original || Client.isUngrabbed() || Client.automatedAttackHeld;
    }

    @Inject(method = "handleKeybinds()V", at = @At("HEAD"))
    private void v5$handleInputEvents(CallbackInfo ci) {
        if (!Client.isInputLocked()) {
            return;
        }

        for (KeyMapping key : options.keyHotbarSlots) {
            if (key.consumeClick()) {
                key.setDown(false);
            }
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void v5$tickRenderLimiter(CallbackInfo ci) {
        int currentDist = options.renderDistance().get();
        CameraType currentPerspective = options.getCameraType();
        int currentFps = options.framerateLimit().get();
        float currentMasterVolume = options.getFinalSoundSourceVolume(SoundSource.MASTER);

        boolean macroEnabled = Client.isMacroEnabled();
        String renderLimiter = Client.getRenderLimiter();
        boolean forcePerspective = Client.isForcePerspective();
        boolean limitFps = Client.getLimitFps();
        boolean muteGame = Client.getMuteGame();

        if (macroEnabled && "Limit Chunks".equals(renderLimiter)) {
            if (v5$savedDistance == null) v5$savedDistance = currentDist;
            if (currentDist != 2) {
                options.renderDistance().set(2);
            }
        } else if (v5$savedDistance != null) {
            if (currentDist != v5$savedDistance) {
                options.renderDistance().set(v5$savedDistance);
            }
            v5$savedDistance = null;
        }

        if (macroEnabled && forcePerspective) {
            if (v5$savedPerspective == null) v5$savedPerspective = currentPerspective;
            if (currentPerspective != CameraType.THIRD_PERSON_BACK) {
                options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        } else if (v5$savedPerspective != null) {
            if (currentPerspective != v5$savedPerspective) {
                options.setCameraType(v5$savedPerspective);
            }
            v5$savedPerspective = null;
        }

        if (macroEnabled && limitFps) {
            if (v5$savedFps == null) v5$savedFps = currentFps;
            if (currentFps != 30) {
                options.framerateLimit().set(30);
            }
        } else if (v5$savedFps != null) {
            if (currentFps != v5$savedFps) {
                int restoreValue = v5$savedFps > 240 ? 260 : v5$savedFps;
                options.framerateLimit().set(restoreValue);
            }
            v5$savedFps = null;
        }

        if (macroEnabled && muteGame) {
            if (v5$savedMasterVolume == null) v5$savedMasterVolume = currentMasterVolume;
            if (Float.compare(currentMasterVolume, 0.0F) != 0) {
                options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0D);
            }
        } else if (v5$savedMasterVolume != null) {
            if (Float.compare(currentMasterVolume, v5$savedMasterVolume) != 0) {
                options.getSoundSourceOptionInstance(SoundSource.MASTER).set((double) v5$savedMasterVolume);
            }
            v5$savedMasterVolume = null;
        }
    }
}
