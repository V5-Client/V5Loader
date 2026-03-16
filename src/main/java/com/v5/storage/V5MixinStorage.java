package com.v5.storage;

import com.chattriggers.ctjs.internal.engine.JSLoader;
import java.util.HashMap;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Undefined;

public final class V5MixinStorage {
    private static final String STORAGE_KEY = "V5Mixin.storage";
    private static final String METHOD_PREFIX = "method_";

    private V5MixinStorage() {}

    @SuppressWarnings("unchecked")
    private static HashMap<String, Object> storage() {
        Object raw = System.getProperties().get(STORAGE_KEY);
        if (raw instanceof HashMap<?, ?> map) {
            return (HashMap<String, Object>) map;
        }

        HashMap<String, Object> created = new HashMap<>();
        System.getProperties().put(STORAGE_KEY, created);
        return created;
    }

    public static Object get(String key, Object defaultValue) {
        HashMap<String, Object> map = storage();
        return map.containsKey(key) ? map.get(key) : defaultValue;
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
            String normalized = sequence.toString().trim().toLowerCase();
            if ("true".equals(normalized)) return true;
            if ("false".equals(normalized)) return false;
            return defaultValue;
        }

        try {
            return ScriptRuntime.toBoolean(value);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static String getString(String key, String defaultValue) {
        Object value = get(key, defaultValue);
        if (value == null || value == Undefined.instance) {
            return defaultValue;
        }

        if (value instanceof String str) {
            return str;
        }

        try {
            return ScriptRuntime.toString(value);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static void set(String key, Object value) {
        storage().put(key, value);
    }

    public static <T> T applyMethod(String methodName, T original, Class<T> expectedType) {
        Object value = storage().get(METHOD_PREFIX + methodName);
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
}
