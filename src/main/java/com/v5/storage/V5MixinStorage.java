package com.v5.storage;

import com.chattriggers.ctjs.internal.engine.JSLoader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Undefined;

public final class V5MixinStorage {
    private static final String METHOD_PREFIX = "method_";
    private static final Map<String, Object> STORAGE = new HashMap<>();

    private V5MixinStorage() {}

    public static Object get(String key, Object defaultValue) {
        return STORAGE.getOrDefault(key, defaultValue);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key, defaultValue);
        if (value == null || value == Undefined.instance) {
            return defaultValue;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        if (value instanceof Number number) {
            return number.doubleValue() != 0.0D;
        }

        if (value instanceof CharSequence sequence) {
            String normalized = sequence.toString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) return true;
            if ("false".equals(normalized)) return false;
            return defaultValue;
        }

        return convertOrDefault(value, ScriptRuntime::toBoolean, defaultValue);
    }

    public static String getString(String key, String defaultValue) {
        Object value = get(key, defaultValue);
        if (value == null || value == Undefined.instance) {
            return defaultValue;
        }

        if (value instanceof String str) {
            return str;
        }

        return convertOrDefault(value, ScriptRuntime::toString, defaultValue);
    }

    public static void set(String key, Object value) {
        STORAGE.put(key, value);
    }

    public static void clear() {
        Object savedDistance = STORAGE.get("savedDistance");
        Object savedPerspective = STORAGE.get("savedPerspective");
        Object savedFps = STORAGE.get("savedFps");
        Object savedMasterVolume = STORAGE.get("savedMasterVolume");
        STORAGE.clear();
        if (savedDistance != null) STORAGE.put("savedDistance", savedDistance);
        if (savedPerspective != null) STORAGE.put("savedPerspective", savedPerspective);
        if (savedFps != null) STORAGE.put("savedFps", savedFps);
        if (savedMasterVolume != null) STORAGE.put("savedMasterVolume", savedMasterVolume);
    }

    public static <T> T applyMethod(String methodName, T original, Class<T> expectedType) {
        Object value = STORAGE.get(METHOD_PREFIX + methodName);
        if (!(value instanceof Callable callable)) {
            return original;
        }

        try {
            Object result = JSLoader.invokeMixin(callable, new Object[] { original });
            if (result == null || result == Undefined.instance) {
                return original;
            }

            if (expectedType.isInstance(result)) {
                return expectedType.cast(result);
            }
        } catch (Throwable ignored) {
        }

        return original;
    }

    private static <T> T convertOrDefault(Object value, Function<Object, T> converter, T defaultValue) {
        try {
            return converter.apply(value);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }
}
