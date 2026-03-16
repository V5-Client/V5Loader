package com.chattriggers.ctjs.api.triggers

import com.chattriggers.ctjs.internal.engine.JSLoader

sealed interface ITriggerType {
    val name: String

    fun triggerAll() {
        JSLoader.execNoArgs(this)
    }

    // THESE ARE HERE FOR BACKWARDS COMPATABILITY. we should probably look to remove deprecated stuff right?
    fun triggerAll(arg0: Any?) {
        JSLoader.exec(this, arg0)
    }

    fun triggerAll(arg0: Any?, arg1: Any?) {
        JSLoader.exec(this, arg0, arg1)
    }

    fun triggerAll(arg0: Any?, arg1: Any?, arg2: Any?) {
        JSLoader.exec(this, arg0, arg1, arg2)
    }

    fun triggerAll(arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?) {
        JSLoader.exec(this, arg0, arg1, arg2, arg3)
    }

    fun triggerAll(arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?) {
        JSLoader.exec(this, arg0, arg1, arg2, arg3, arg4)
    }

    fun triggerAll(vararg args: Any?) {
        if (args.isEmpty()) {
            JSLoader.execNoArgs(this)
        } else {
            JSLoader.exec(this, args)
        }
    }
}

enum class TriggerType : ITriggerType {
    // client
    CHAT,
    ACTION_BAR,
    TICK,
    STEP,
    GAME_UNLOAD,
    GAME_LOAD,
    CLICKED,
    SCROLLED,
    DRAGGED,
    GUI_OPENED,
    MESSAGE_SENT,
    ITEM_TOOLTIP,
    PLAYER_INTERACT,
    GUI_KEY,
    GUI_MOUSE_CLICK,
    GUI_MOUSE_DRAG,
    PACKET_SENT,
    PACKET_RECEIVED,
    SERVER_CONNECT,
    SERVER_DISCONNECT,
    GUI_CLOSED,
    DROP_ITEM,

    // rendering
    PRE_RENDER_WORLD,
    POST_RENDER_WORLD,
    BLOCK_HIGHLIGHT,
    RENDER_OVERLAY,
    RENDER_PLAYER_LIST,
    RENDER_ENTITY,
    RENDER_BLOCK_ENTITY,
    GUI_RENDER,
    POST_GUI_RENDER,

    // world
    SOUND_PLAY,
    WORLD_LOAD,
    WORLD_UNLOAD,
    SPAWN_PARTICLE,
    ENTITY_DEATH,
    ENTITY_DAMAGE,

    // misc
    COMMAND,
    OTHER
}

data class CustomTriggerType(override val name: String) : ITriggerType
