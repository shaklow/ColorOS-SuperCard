package dev.local.supercardhost

import kotlin.math.exp
import kotlin.math.sqrt

/** Original finger-follow spring: stiffness 1500, damping ratio 1, retargeted without restarting. */
internal class CardFollowSpring(initial: Float) {
    var position = initial
        private set
    var velocity = 0f
        private set
    fun advance(target: Float, seconds: Float) {
        if (seconds <= 0f) return
        val omega = sqrt(1500f)
        val a = position - target
        val b = velocity + omega * a
        val decay = exp(-omega * seconds)
        position = target + (a + b * seconds) * decay
        velocity = (b - omega * (a + b * seconds)) * decay
    }
}
