package com.chattriggers.ctjs.internal.utils

import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UKeyboard

object InputCompat {
    private val scriptToNative = /*? if >=26.3 {*//*buildMap {
        for (key in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z) put(key, InputConstants.KEY_A + key - GLFW.GLFW_KEY_A)
        for (key in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9) put(key, InputConstants.KEY_1 + key - GLFW.GLFW_KEY_1)
        for (key in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12) put(key, InputConstants.KEY_F1 + key - GLFW.GLFW_KEY_F1)
        for (key in GLFW.GLFW_KEY_F13..GLFW.GLFW_KEY_F24) put(key, InputConstants.KEY_F13 + key - GLFW.GLFW_KEY_F13)
        for (key in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_9) put(key, InputConstants.KEY_NUMPAD1 + key - GLFW.GLFW_KEY_KP_1)
        put(GLFW.GLFW_KEY_0, InputConstants.KEY_0)
        put(GLFW.GLFW_KEY_KP_0, InputConstants.KEY_NUMPAD0)
        put(GLFW.GLFW_KEY_ESCAPE, InputConstants.KEY_ESCAPE)
        put(GLFW.GLFW_KEY_ENTER, InputConstants.KEY_RETURN)
        put(GLFW.GLFW_KEY_TAB, InputConstants.KEY_TAB)
        put(GLFW.GLFW_KEY_BACKSPACE, InputConstants.KEY_BACKSPACE)
        put(GLFW.GLFW_KEY_INSERT, InputConstants.KEY_INSERT)
        put(GLFW.GLFW_KEY_DELETE, InputConstants.KEY_DELETE)
        put(GLFW.GLFW_KEY_RIGHT, InputConstants.KEY_RIGHT)
        put(GLFW.GLFW_KEY_LEFT, InputConstants.KEY_LEFT)
        put(GLFW.GLFW_KEY_DOWN, InputConstants.KEY_DOWN)
        put(GLFW.GLFW_KEY_UP, InputConstants.KEY_UP)
        put(GLFW.GLFW_KEY_PAGE_UP, InputConstants.KEY_PAGEUP)
        put(GLFW.GLFW_KEY_PAGE_DOWN, InputConstants.KEY_PAGEDOWN)
        put(GLFW.GLFW_KEY_HOME, InputConstants.KEY_HOME)
        put(GLFW.GLFW_KEY_END, InputConstants.KEY_END)
        put(GLFW.GLFW_KEY_CAPS_LOCK, InputConstants.KEY_CAPSLOCK)
        put(GLFW.GLFW_KEY_SCROLL_LOCK, InputConstants.KEY_SCROLLLOCK)
        put(GLFW.GLFW_KEY_NUM_LOCK, InputConstants.KEY_NUMLOCK)
        put(GLFW.GLFW_KEY_PRINT_SCREEN, InputConstants.KEY_PRINTSCREEN)
        put(GLFW.GLFW_KEY_PAUSE, InputConstants.KEY_PAUSE)
        put(GLFW.GLFW_KEY_SPACE, InputConstants.KEY_SPACE)
        put(GLFW.GLFW_KEY_APOSTROPHE, InputConstants.KEY_APOSTROPHE)
        put(GLFW.GLFW_KEY_COMMA, InputConstants.KEY_COMMA)
        put(GLFW.GLFW_KEY_MINUS, InputConstants.KEY_MINUS)
        put(GLFW.GLFW_KEY_PERIOD, InputConstants.KEY_PERIOD)
        put(GLFW.GLFW_KEY_SLASH, InputConstants.KEY_SLASH)
        put(GLFW.GLFW_KEY_SEMICOLON, InputConstants.KEY_SEMICOLON)
        put(GLFW.GLFW_KEY_EQUAL, InputConstants.KEY_EQUALS)
        put(GLFW.GLFW_KEY_LEFT_BRACKET, InputConstants.KEY_LBRACKET)
        put(GLFW.GLFW_KEY_BACKSLASH, InputConstants.KEY_BACKSLASH)
        put(GLFW.GLFW_KEY_RIGHT_BRACKET, InputConstants.KEY_RBRACKET)
        put(GLFW.GLFW_KEY_GRAVE_ACCENT, InputConstants.KEY_GRAVE)
        put(GLFW.GLFW_KEY_KP_DECIMAL, 99)
        put(GLFW.GLFW_KEY_KP_DIVIDE, 84)
        put(GLFW.GLFW_KEY_KP_MULTIPLY, InputConstants.KEY_MULTIPLY)
        put(GLFW.GLFW_KEY_KP_SUBTRACT, 86)
        put(GLFW.GLFW_KEY_KP_ADD, InputConstants.KEY_ADD)
        put(GLFW.GLFW_KEY_KP_ENTER, InputConstants.KEY_NUMPADENTER)
        put(GLFW.GLFW_KEY_KP_EQUAL, InputConstants.KEY_NUMPADEQUALS)
        put(GLFW.GLFW_KEY_LEFT_SHIFT, InputConstants.KEY_LSHIFT)
        put(GLFW.GLFW_KEY_LEFT_CONTROL, InputConstants.KEY_LCONTROL)
        put(GLFW.GLFW_KEY_LEFT_ALT, InputConstants.KEY_LALT)
        put(GLFW.GLFW_KEY_LEFT_SUPER, InputConstants.KEY_LGUI)
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, InputConstants.KEY_RSHIFT)
        put(GLFW.GLFW_KEY_RIGHT_CONTROL, InputConstants.KEY_RCONTROL)
        put(GLFW.GLFW_KEY_RIGHT_ALT, InputConstants.KEY_RALT)
        put(GLFW.GLFW_KEY_RIGHT_SUPER, InputConstants.KEY_RGUI)
        put(GLFW.GLFW_KEY_MENU, 101)
    }*//*?} else {*/ emptyMap<Int, Int>() /*?}*/

    private val nativeToScript = scriptToNative.entries.associate { (script, native) -> native to script }

    @JvmStatic
    fun normalizeKeyCode(keyCode: Int): Int = /*? if >=26.3 {*//*when {
        keyCode in UKeyboard.KEY_A..UKeyboard.KEY_Z -> GLFW.GLFW_KEY_A + keyCode - UKeyboard.KEY_A
        else -> universalToScript[keyCode] ?: keyCode
    }*//*?} else {*/ keyCode /*?}*/

    @JvmStatic
    fun toNativeKeyCode(keyCode: Int): Int = /*? if >=26.3 {*//*
        scriptToNative[normalizeKeyCode(keyCode)] ?: InputConstants.UNKNOWN.value
    *//*?} else {*/ keyCode /*?}*/

    @JvmStatic
    fun fromNativeKeyCode(keyCode: Int): Int = /*? if >=26.3 {*//*
        nativeToScript[keyCode] ?: GLFW.GLFW_KEY_UNKNOWN
    *//*?} else {*/ keyCode /*?}*/

    @JvmStatic
    fun key(keyCode: Int): InputConstants.Key =
        /*? if >=26.3 {*//*InputConstants.Type.KEYBOARD*//*?} else {*/ InputConstants.Type.KEYSYM /*?}*/
            .getOrCreate(toNativeKeyCode(keyCode))

    @JvmStatic
    fun fromNativeMouseButton(button: Int): Int = /*? if >=26.3 {*//*if (button < 0) button else button - 1*//*?} else {*/ button /*?}*/

    // UniversalCraft exposes SDL keycodes on 26.3; scripts and saved bindings keep GLFW's values.
    private val universalToScript = /*? if >=26.3 {*//*mapOf(
        UKeyboard.KEY_NONE to GLFW.GLFW_KEY_UNKNOWN,
        UKeyboard.KEY_ESCAPE to GLFW.GLFW_KEY_ESCAPE,
        UKeyboard.KEY_LMETA to GLFW.GLFW_KEY_LEFT_SUPER,
        UKeyboard.KEY_RMETA to GLFW.GLFW_KEY_RIGHT_SUPER,
        UKeyboard.KEY_LCONTROL to GLFW.GLFW_KEY_LEFT_CONTROL,
        UKeyboard.KEY_RCONTROL to GLFW.GLFW_KEY_RIGHT_CONTROL,
        UKeyboard.KEY_LSHIFT to GLFW.GLFW_KEY_LEFT_SHIFT,
        UKeyboard.KEY_RSHIFT to GLFW.GLFW_KEY_RIGHT_SHIFT,
        UKeyboard.KEY_LMENU to GLFW.GLFW_KEY_LEFT_ALT,
        UKeyboard.KEY_RMENU to GLFW.GLFW_KEY_RIGHT_ALT,
        UKeyboard.KEY_MENU to GLFW.GLFW_KEY_MENU,
        UKeyboard.KEY_BACKSPACE to GLFW.GLFW_KEY_BACKSPACE,
        UKeyboard.KEY_ENTER to GLFW.GLFW_KEY_ENTER,
        UKeyboard.KEY_TAB to GLFW.GLFW_KEY_TAB,
        UKeyboard.KEY_CAPITAL to GLFW.GLFW_KEY_CAPS_LOCK,
        UKeyboard.KEY_LEFT to GLFW.GLFW_KEY_LEFT,
        UKeyboard.KEY_UP to GLFW.GLFW_KEY_UP,
        UKeyboard.KEY_RIGHT to GLFW.GLFW_KEY_RIGHT,
        UKeyboard.KEY_DOWN to GLFW.GLFW_KEY_DOWN,
        UKeyboard.KEY_NUMLOCK to GLFW.GLFW_KEY_NUM_LOCK,
        UKeyboard.KEY_SCROLL to GLFW.GLFW_KEY_SCROLL_LOCK,
        UKeyboard.KEY_SUBTRACT to GLFW.GLFW_KEY_KP_SUBTRACT,
        UKeyboard.KEY_ADD to GLFW.GLFW_KEY_KP_ADD,
        UKeyboard.KEY_DIVIDE to GLFW.GLFW_KEY_KP_DIVIDE,
        UKeyboard.KEY_DECIMAL to GLFW.GLFW_KEY_KP_DECIMAL,
        UKeyboard.KEY_MULTIPLY to GLFW.GLFW_KEY_KP_MULTIPLY,
        UKeyboard.KEY_NUMPAD0 to GLFW.GLFW_KEY_KP_0,
        UKeyboard.KEY_NUMPAD1 to GLFW.GLFW_KEY_KP_1,
        UKeyboard.KEY_NUMPAD2 to GLFW.GLFW_KEY_KP_2,
        UKeyboard.KEY_NUMPAD3 to GLFW.GLFW_KEY_KP_3,
        UKeyboard.KEY_NUMPAD4 to GLFW.GLFW_KEY_KP_4,
        UKeyboard.KEY_NUMPAD5 to GLFW.GLFW_KEY_KP_5,
        UKeyboard.KEY_NUMPAD6 to GLFW.GLFW_KEY_KP_6,
        UKeyboard.KEY_NUMPAD7 to GLFW.GLFW_KEY_KP_7,
        UKeyboard.KEY_NUMPAD8 to GLFW.GLFW_KEY_KP_8,
        UKeyboard.KEY_NUMPAD9 to GLFW.GLFW_KEY_KP_9,
        UKeyboard.KEY_NUMPADENTER to GLFW.GLFW_KEY_KP_ENTER,
        UKeyboard.KEY_F1 to GLFW.GLFW_KEY_F1,
        UKeyboard.KEY_F2 to GLFW.GLFW_KEY_F2,
        UKeyboard.KEY_F3 to GLFW.GLFW_KEY_F3,
        UKeyboard.KEY_F4 to GLFW.GLFW_KEY_F4,
        UKeyboard.KEY_F5 to GLFW.GLFW_KEY_F5,
        UKeyboard.KEY_F6 to GLFW.GLFW_KEY_F6,
        UKeyboard.KEY_F7 to GLFW.GLFW_KEY_F7,
        UKeyboard.KEY_F8 to GLFW.GLFW_KEY_F8,
        UKeyboard.KEY_F9 to GLFW.GLFW_KEY_F9,
        UKeyboard.KEY_F10 to GLFW.GLFW_KEY_F10,
        UKeyboard.KEY_F11 to GLFW.GLFW_KEY_F11,
        UKeyboard.KEY_F12 to GLFW.GLFW_KEY_F12,
        UKeyboard.KEY_F13 to GLFW.GLFW_KEY_F13,
        UKeyboard.KEY_F14 to GLFW.GLFW_KEY_F14,
        UKeyboard.KEY_F15 to GLFW.GLFW_KEY_F15,
        UKeyboard.KEY_F16 to GLFW.GLFW_KEY_F16,
        UKeyboard.KEY_F17 to GLFW.GLFW_KEY_F17,
        UKeyboard.KEY_F18 to GLFW.GLFW_KEY_F18,
        UKeyboard.KEY_F19 to GLFW.GLFW_KEY_F19,
        UKeyboard.KEY_DELETE to GLFW.GLFW_KEY_DELETE,
        UKeyboard.KEY_HOME to GLFW.GLFW_KEY_HOME,
        UKeyboard.KEY_END to GLFW.GLFW_KEY_END,
    )*//*?} else {*/ emptyMap<Int, Int>() /*?}*/
}

// Stable script values used by GLFW-era ChatTriggers bindings.
private object GLFW {
    const val GLFW_KEY_UNKNOWN = -1
    const val GLFW_KEY_SPACE = 32
    const val GLFW_KEY_APOSTROPHE = 39
    const val GLFW_KEY_COMMA = 44
    const val GLFW_KEY_MINUS = 45
    const val GLFW_KEY_PERIOD = 46
    const val GLFW_KEY_SLASH = 47
    const val GLFW_KEY_0 = 48
    const val GLFW_KEY_1 = 49
    const val GLFW_KEY_9 = 57
    const val GLFW_KEY_SEMICOLON = 59
    const val GLFW_KEY_EQUAL = 61
    const val GLFW_KEY_A = 65
    const val GLFW_KEY_Z = 90
    const val GLFW_KEY_LEFT_BRACKET = 91
    const val GLFW_KEY_BACKSLASH = 92
    const val GLFW_KEY_RIGHT_BRACKET = 93
    const val GLFW_KEY_GRAVE_ACCENT = 96
    const val GLFW_KEY_ESCAPE = 256
    const val GLFW_KEY_ENTER = 257
    const val GLFW_KEY_TAB = 258
    const val GLFW_KEY_BACKSPACE = 259
    const val GLFW_KEY_INSERT = 260
    const val GLFW_KEY_DELETE = 261
    const val GLFW_KEY_RIGHT = 262
    const val GLFW_KEY_LEFT = 263
    const val GLFW_KEY_DOWN = 264
    const val GLFW_KEY_UP = 265
    const val GLFW_KEY_PAGE_UP = 266
    const val GLFW_KEY_PAGE_DOWN = 267
    const val GLFW_KEY_HOME = 268
    const val GLFW_KEY_END = 269
    const val GLFW_KEY_CAPS_LOCK = 280
    const val GLFW_KEY_SCROLL_LOCK = 281
    const val GLFW_KEY_NUM_LOCK = 282
    const val GLFW_KEY_PRINT_SCREEN = 283
    const val GLFW_KEY_PAUSE = 284
    const val GLFW_KEY_F1 = 290
    const val GLFW_KEY_F2 = 291
    const val GLFW_KEY_F3 = 292
    const val GLFW_KEY_F4 = 293
    const val GLFW_KEY_F5 = 294
    const val GLFW_KEY_F6 = 295
    const val GLFW_KEY_F7 = 296
    const val GLFW_KEY_F8 = 297
    const val GLFW_KEY_F9 = 298
    const val GLFW_KEY_F10 = 299
    const val GLFW_KEY_F11 = 300
    const val GLFW_KEY_F12 = 301
    const val GLFW_KEY_F13 = 302
    const val GLFW_KEY_F14 = 303
    const val GLFW_KEY_F15 = 304
    const val GLFW_KEY_F16 = 305
    const val GLFW_KEY_F17 = 306
    const val GLFW_KEY_F18 = 307
    const val GLFW_KEY_F19 = 308
    const val GLFW_KEY_F24 = 313
    const val GLFW_KEY_KP_0 = 320
    const val GLFW_KEY_KP_1 = 321
    const val GLFW_KEY_KP_2 = 322
    const val GLFW_KEY_KP_3 = 323
    const val GLFW_KEY_KP_4 = 324
    const val GLFW_KEY_KP_5 = 325
    const val GLFW_KEY_KP_6 = 326
    const val GLFW_KEY_KP_7 = 327
    const val GLFW_KEY_KP_8 = 328
    const val GLFW_KEY_KP_9 = 329
    const val GLFW_KEY_KP_DECIMAL = 330
    const val GLFW_KEY_KP_DIVIDE = 331
    const val GLFW_KEY_KP_MULTIPLY = 332
    const val GLFW_KEY_KP_SUBTRACT = 333
    const val GLFW_KEY_KP_ADD = 334
    const val GLFW_KEY_KP_ENTER = 335
    const val GLFW_KEY_KP_EQUAL = 336
    const val GLFW_KEY_LEFT_SHIFT = 340
    const val GLFW_KEY_LEFT_CONTROL = 341
    const val GLFW_KEY_LEFT_ALT = 342
    const val GLFW_KEY_LEFT_SUPER = 343
    const val GLFW_KEY_RIGHT_SHIFT = 344
    const val GLFW_KEY_RIGHT_CONTROL = 345
    const val GLFW_KEY_RIGHT_ALT = 346
    const val GLFW_KEY_RIGHT_SUPER = 347
    const val GLFW_KEY_MENU = 348
}
