package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Shadow @Final public GameOptions options;

    @Inject(method = "handleInputEvents()V", at = @At("HEAD"))
    private void v5$handleInputEvents(CallbackInfo ci) {
        if (!V5MixinStorage.getBoolean("inputLocked", false)) {
            return;
        }

        for (KeyBinding key : options.hotbarKeys) {
            if (key.wasPressed()) {
                key.setPressed(false);
            }
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void v5$tickRenderLimiter(CallbackInfo ci) {
        int currentDist = options.getViewDistance().getValue();
        Perspective currentPerspective = options.getPerspective();
        int currentFps = options.getMaxFps().getValue();
        float currentMasterVolume = options.getSoundVolume(SoundCategory.MASTER);

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
                    options.getSoundVolumeOption(SoundCategory.MASTER).setValue(0.0D);
                }
            } else if (savedMasterVolumeObj instanceof Number savedMasterVolumeNum) {
                float savedMasterVolume = savedMasterVolumeNum.floatValue();
                if (Float.compare(currentMasterVolume, savedMasterVolume) != 0) {
                    options.getSoundVolumeOption(SoundCategory.MASTER).setValue((double) savedMasterVolume);
                }
                V5MixinStorage.set("savedMasterVolume", null);
            }

            if ("Limit Chunks".equals(renderLimiter) && currentDist != 2) {
                options.getViewDistance().setValue(2);
            }

            if (limitFps) {
                options.getMaxFps().setValue(30);
            }

            if (forcePerspective && currentPerspective != Perspective.THIRD_PERSON_BACK) {
                options.setPerspective(Perspective.THIRD_PERSON_BACK);
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
                options.getViewDistance().setValue(savedDistance);
            }
            V5MixinStorage.set("savedDistance", null);
        }

        if (forcePerspective && savedPerspectiveObj instanceof Perspective savedPerspective) {
            if (currentPerspective != savedPerspective) {
                options.setPerspective(savedPerspective);
            }
            V5MixinStorage.set("savedPerspective", null);
        }

        if (savedFpsObj instanceof Number savedFpsNum) {
            int savedFps = savedFpsNum.intValue();
            if (currentFps != savedFps) {
                int restoreValue = savedFps > 240 ? 260 : savedFps;
                options.getMaxFps().setValue(restoreValue);
            }
            V5MixinStorage.set("savedFps", null);
        }

        if (savedMasterVolumeObj instanceof Number savedMasterVolumeNum) {
            float savedMasterVolume = savedMasterVolumeNum.floatValue();
            if (Float.compare(currentMasterVolume, savedMasterVolume) != 0) {
                options.getSoundVolumeOption(SoundCategory.MASTER).setValue((double) savedMasterVolume);
            }
            V5MixinStorage.set("savedMasterVolume", null);
        }
    }
}
