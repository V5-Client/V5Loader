package com.v5.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "moe.nea.firmament.features.misc.ModAnnouncer")
public class FirmamentModAnnouncerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(FirmamentModAnnouncerMixin.class);

    @WrapMethod(method = "onServerJoin", require = 0)
    private void onServerJoin(@Coerce Object event, Operation<Void> original) {
        LOGGER.info("Firmament detected, disabling mod announcer.");
    }
}
