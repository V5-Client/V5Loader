package com.v5.storage;

import java.awt.Color;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class ProfileHiderProcessor {
    private ProfileHiderProcessor() {}

    public static Component process(Component original) {
        if (!V5MixinStorage.getBoolean("profileHiderEnabled", false)) {
            return original;
        }

        String username = Minecraft.getInstance().getUser().getName();
        String replacement = V5MixinStorage.getString("profileHiderReplacement", "Hidden");
        MutableComponent result = Component.empty();

        original.visit(
                (style, content) -> {
                    String[] parts = content.split(Pattern.quote(username), -1);
                    for (int i = 0; i < parts.length; i++) {
                        if (!parts[i].isEmpty()) {
                            result.append(Component.literal(parts[i]).setStyle(style));
                        }
                        if (i < parts.length - 1) {
                            result.append(replacement(replacement));
                        }
                    }
                    return Optional.empty();
                },
                Style.EMPTY);
        return result;
    }

    private static Component replacement(String value) {
        if (value.matches("^#[0-9a-fA-F]{6}.+")) {
            return Component.literal(value.substring(7)).withColor(Integer.parseInt(value.substring(1, 7), 16));
        }
        if (value.indexOf('&') >= 0 || value.indexOf('§') >= 0) {
            return Component.literal(value.replace('&', '§'));
        }

        MutableComponent result = Component.empty();
        for (int i = 0; i < value.length(); i++) {
            float hue = (System.currentTimeMillis() % 2000) / 2000F + i / 40F;
            int color = Color.HSBtoRGB(hue % 1, 0.8F, 1F) & 0xFFFFFF;
            result.append(Component.literal(String.valueOf(value.charAt(i))).withColor(color).withStyle(style -> style.withBold(true)));
        }
        return result;
    }
}
