package com.chattriggers.ctjs.internal.mixins;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(
    targets = /*? if >=26.3 {*//*"com.mojang.authlib.services.MinecraftServicesKeyInfo"*//*?} else {*/ "com.mojang.authlib.yggdrasil.YggdrasilServicesKeyInfo" /*?}*/,
    remap = false
)
public class YggdrasilServicesKeyInfoMixin {
    @Redirect(
        method = "validateProperty(Lcom/mojang/authlib/properties/Property;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0,
        remap = false
    )
    private void redirectLoggerError(Logger instance, String s, Object o, Object o1) {
        // Do nothing - suppress the log
    }
}
