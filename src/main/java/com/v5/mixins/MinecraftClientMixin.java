package com.v5.mixins;

import com.v5.storage.V5MixinStorage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Unique private static final boolean MAGICAL_TRUTH = true;

    @Shadow @Final public GameOptions options;
    @Shadow @Nullable public net.minecraft.client.gui.screen.Screen currentScreen;
    @Shadow @Nullable public ClientPlayerEntity player;
    @Shadow @Nullable public ClientPlayerInteractionManager interactionManager;
    @Shadow @Nullable public HitResult crosshairTarget;
    @Shadow private int itemUseCooldown;

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
    private void v5$tickClick(CallbackInfo ci) {
        boolean isNukerActive = V5MixinStorage.getBoolean("nukerActive", false);
        boolean shouldClick = V5MixinStorage.getBoolean("shouldClick", false);
        boolean macroEnabled = V5MixinStorage.getBoolean("macroEnabled", false);

        if (!macroEnabled || (!isNukerActive && !shouldClick)) {
            return;
        }

        if (currentScreen == null || player == null || interactionManager == null || !MAGICAL_TRUTH) {
            return;
        }

        if (itemUseCooldown > 0) {
            return;
        }

        player.swingHand(Hand.MAIN_HAND);

        HitResult target = crosshairTarget;
        if (target == null) {
            return;
        }

        HitResult.Type type = target.getType();
        if (type == HitResult.Type.ENTITY && target instanceof EntityHitResult entityTarget) {
            interactionManager.attackEntity(player, entityTarget.getEntity());
        } else if (type == HitResult.Type.BLOCK && target instanceof BlockHitResult blockTarget) {
            interactionManager.attackBlock(blockTarget.getBlockPos(), blockTarget.getSide());
        }

        if (!isNukerActive) {
            V5MixinStorage.set("shouldClick", false);
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void v5$tickRenderLimiter(CallbackInfo ci) {
        int currentDist = options.getViewDistance().getValue();
        Perspective currentPerspective = options.getPerspective();
        int currentFps = options.getMaxFps().getValue();

        boolean macroEnabled = V5MixinStorage.getBoolean("macroEnabled", false);
        String renderLimiter = V5MixinStorage.getString("renderLimiter", "Off");
        boolean forcePerspective = V5MixinStorage.getBoolean("forcePerspective", false);
        boolean limitFps = V5MixinStorage.getBoolean("limitFps", false);

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
    }
}
