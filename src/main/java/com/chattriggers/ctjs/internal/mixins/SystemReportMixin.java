package com.chattriggers.ctjs.internal.mixins;

import com.chattriggers.ctjs.internal.engine.module.Module;
import com.chattriggers.ctjs.internal.engine.module.ModuleManager;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Mixin(SystemReport.class)
public abstract class SystemReportMixin {
    @Shadow
    public abstract void setDetail(String string, Supplier<String> supplier);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void addModules(CallbackInfo ci) {
        setDetail("ChatTriggers Modules", () -> ModuleManager.INSTANCE.getCachedModules().stream()
            .sorted(Comparator.comparing(Module::getName))
            .map(module -> {
                var version = module.getMetadata().getVersion();
                return "\n\t\t" + module.getName() + ": " +
                    (version == null ? "No module version specified" : "v" + version);
            })
            .collect(Collectors.joining()));
    }
}
