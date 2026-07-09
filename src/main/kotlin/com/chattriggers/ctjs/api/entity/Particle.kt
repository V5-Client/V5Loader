package com.chattriggers.ctjs.api.entity

import com.chattriggers.ctjs.api.CTWrapper
import com.chattriggers.ctjs.api.render.Renderer
import com.chattriggers.ctjs.internal.mixins.ParticleAccessor
import com.chattriggers.ctjs.MCParticle
import com.chattriggers.ctjs.internal.utils.asMixin

class Particle(override val mcValue: MCParticle) : CTWrapper<MCParticle> {
    private val mixed: ParticleAccessor = mcValue.asMixin()

    var x
        get() = mixed.x
        set(value) {
            mixed.x = value
        }

    var y
        get() = mixed.y
        set(value) {
            mixed.y = value
        }

    var z
        get() = mixed.z
        set(value) {
            mixed.z = value
        }

    var lastX
        get() = mixed.xo
        set(value) {
            mixed.xo = value
        }

    var lastY
        get() = mixed.yo
        set(value) {
            mixed.yo = value
        }

    var lastZ
        get() = mixed.zo
        set(value) {
            mixed.zo = value
        }

    val renderX get() = lastX + (x - lastX) * Renderer.partialTicks
    val renderY get() = lastY + (y - lastY) * Renderer.partialTicks
    val renderZ get() = lastZ + (z - lastZ) * Renderer.partialTicks

    var motionX
        get() = mixed.xd
        set(value) {
            mixed.xd = value
        }

    var motionY
        get() = mixed.yd
        set(value) {
            mixed.yd = value
        }

    var motionZ
        get() = mixed.zd
        set(value) {
            mixed.zd = value
        }

//    var red by mixed::red
//    var green by mixed::green
//    var blue by mixed::blue
//    var alpha by mixed::alpha

    var age
        get() = mixed.age
        set(value) {
            mixed.age = value
        }

    var dead
        get() = mixed.removed
        set(value) {
            mixed.removed = value
        }

    fun scale(scale: Float) = apply {
        mcValue.scale(scale)
    }

    /**
     * Sets the amount of ticks this particle will live for
     *
     * @param maxAge the particle's max age (in ticks)
     */
    fun setMaxAge(maxAge: Int) = apply {
        mcValue.setLifetime(maxAge)
    }

    fun remove() = apply {
        mcValue.remove()
    }

    override fun toString() =
        "Particle(type=${mcValue.javaClass.simpleName}, pos=($x, $y, $z), age=$age)"
}
