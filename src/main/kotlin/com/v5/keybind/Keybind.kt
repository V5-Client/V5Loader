package com.v5.keybind

import com.v5.mixins.GameOptionsAccessorMixin
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.apache.commons.lang3.ArrayUtils
import java.util.concurrent.ConcurrentHashMap

object KeyBindUtils {
    private val keyBinds = ConcurrentHashMap<String, WrappedKeyBind>()
    private var initialized = false
    private val v5Category = KeyBinding.Category(Identifier.of("v5", "v5_modules"))

    @JvmStatic
    fun init() {
        clearMinecraftOptions()

        if (initialized) return
        initialized = true

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            keyBinds.values.forEach { it.update() }
        }
    }

    @JvmStatic
    fun create(id: String, name: String, keyCode: Int): WrappedKeyBind {
        val existing = keyBinds[id]
        if (existing != null) {
            existing.registerToGame()
            return existing
        }

        val newBind = WrappedKeyBind("v5.key.$name", keyCode, v5Category)
        keyBinds[id] = newBind
        newBind.registerToGame()
        return newBind
    }

    private fun clearMinecraftOptions() {
        val client = MinecraftClient.getInstance()
        val options = client.options ?: return
        val accessor = options as GameOptionsAccessorMixin

        val cleanKeys = options.allKeys.filter {
            !it.boundKeyTranslationKey.startsWith("v5.key.")
        }.toTypedArray()

        accessor.setAllKeys(cleanKeys)
        KeyBinding.updateKeysByCode()
    }

    class WrappedKeyBind(val description: String, val keyCode: Int, val category: KeyBinding.Category) {
        val keyBinding: KeyBinding by lazy {
            KeyBinding(description, InputUtil.Type.KEYSYM, keyCode, category)
        }

        private var onPress: Runnable? = null

        fun onKeyPress(runnable: Runnable) = apply { onPress = runnable }

        internal fun registerToGame() {
            val client = MinecraftClient.getInstance()
            val options = client.options ?: return
            val accessor = options as GameOptionsAccessorMixin

            if (options.allKeys.any { it === keyBinding }) return

            val newKeys = ArrayUtils.add(options.allKeys, keyBinding)
            accessor.setAllKeys(newKeys)
            KeyBinding.updateKeysByCode()
        }

        internal fun update() {
            while (keyBinding.wasPressed()) {
                onPress?.run()
            }
        }
    }
}