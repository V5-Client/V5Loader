package com.v5.mixins;

import com.chattriggers.ctjs.api.client.Client;
import com.v5.storage.V5MixinStorage;
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
        return original || Client.automatedAttackHeld;
    }

    @Inject(method = "handleKeybinds()V", at = @At("HEAD"))
    private void v5$handleInputEvents(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("inputLocked", false)) {
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

        boolean macroEnabled = V5MixinStorage.getBoolean("macroEnabled", false);
        String renderLimiter = V5MixinStorage.getString("renderLimiter", "Off");
        boolean forcePerspective = V5MixinStorage.getBoolean("forcePerspective", false);
        boolean limitFps = V5MixinStorage.getBoolean("limitFps", false);
        boolean muteGame = V5MixinStorage.getBoolean("muteGame", false);

        if (macroEnabled) {
            if (V5MixinStorage.get("savedDistance", null) == null) {
                V5MixinStorage.set("savedDistance", currentDist);
            }
            if (V5MixinStorage.get("savedPerspective", null) == null) {
                V5MixinStorage.set("savedPerspective", currentPerspective);
            }
            if (V5MixinStorage.get("savedFps", null) == null) {
                V5MixinStorage.set("savedFps", currentFps);
            }

            Object savedMasterVolumeObj = V5MixinStorage.get("savedMasterVolume", null);
            if (muteGame) {
                if (savedMasterVolumeObj == null) {
                    V5MixinStorage.set("savedMasterVolume", currentMasterVolume);
                }
                if (Float.compare(currentMasterVolume, 0.0F) != 0) {
                    options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0D);
                }
            } else if (savedMasterVolumeObj instanceof Number savedMasterVolumeNum) {
                float savedMasterVolume = savedMasterVolumeNum.floatValue();
                if (Float.compare(currentMasterVolume, savedMasterVolume) != 0) {
                    options.getSoundSourceOptionInstance(SoundSource.MASTER).set((double) savedMasterVolume);
                }
                V5MixinStorage.set("savedMasterVolume", null);
            }

            if ("Limit Chunks".equals(renderLimiter) && currentDist != 2) {
                options.renderDistance().set(2);
            }

            if (limitFps) {
                options.framerateLimit().set(30);
            }

            if (forcePerspective && currentPerspective != CameraType.THIRD_PERSON_BACK) {
                options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            return;
        }

        Object savedDistanceObj = V5MixinStorage.get("savedDistance", null);
        Object savedPerspectiveObj = V5MixinStorage.get("savedPerspective", null);
        Object savedFpsObj = V5MixinStorage.get("savedFps", null);
        Object savedMasterVolumeObj = V5MixinStorage.get("savedMasterVolume", null);

        if ("Limit Chunks".equals(renderLimiter) && savedDistanceObj instanceof Number savedDistanceNum) {
            int savedDistance = savedDistanceNum.intValue();
            if (currentDist != savedDistance) {
                options.renderDistance().set(savedDistance);
            }
            V5MixinStorage.set("savedDistance", null);
        }

        if (forcePerspective && savedPerspectiveObj instanceof CameraType savedPerspective) {
            if (currentPerspective != savedPerspective) {
                options.setCameraType(savedPerspective);
            }
            V5MixinStorage.set("savedPerspective", null);
        }

        if (savedFpsObj instanceof Number savedFpsNum) {
            int savedFps = savedFpsNum.intValue();
            if (currentFps != savedFps) {
                int restoreValue = savedFps > 240 ? 260 : savedFps;
                options.framerateLimit().set(restoreValue);
            }
            V5MixinStorage.set("savedFps", null);
        }

        if (savedMasterVolumeObj instanceof Number savedMasterVolumeNum) {
            float savedMasterVolume = savedMasterVolumeNum.floatValue();
            if (Float.compare(currentMasterVolume, savedMasterVolume) != 0) {
                options.getSoundSourceOptionInstance(SoundSource.MASTER).set((double) savedMasterVolume);
            }
            V5MixinStorage.set("savedMasterVolume", null);
        }
    }
}
