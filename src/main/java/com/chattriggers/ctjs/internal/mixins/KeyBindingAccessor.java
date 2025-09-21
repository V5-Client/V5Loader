package com.chattriggers.ctjs.internal.mixins;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor
    InputUtil.Key getBoundKey();

    @Accessor
    int getTimesPressed();

    @Mixin(KeyBinding.Category.class)
    interface Category {
        @Accessor("CATEGORIES")
        static List<KeyBinding.Category> getCategoryList() { throw new IllegalStateException(); }
    }
}
