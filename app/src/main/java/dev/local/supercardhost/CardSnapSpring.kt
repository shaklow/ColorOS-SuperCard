package dev.local.supercardhost

import kotlin.math.*

/** Original QuickSwitchAnimationConfig: mass=1, tension=145, friction=20. */
internal class CardSnapSpring(from: Float, private val target: Float, velocity: Float) {
    private val omega = sqrt(45f)
    private val a = from - target
    private val b = (velocity.coerceIn(-20_000f, 20_000f) + 10f * a) / omega
    fun position(t: Float) = target + exp(-10f * t) * (a * cos(omega * t) + b * sin(omega * t))
    fun velocity(t: Float) = exp(-10f * t) *
        ((b * omega - 10f * a) * cos(omega * t) - (a * omega + 10f * b) * sin(omega * t))
    val duration: Long = run {
        // Find the final threshold crossing, not a momentary pause at the rebound's turning point.
        var time = 3f
        while (time > .01f && abs(position(time) - target) < 1f && abs(velocity(time)) < 1f) time -= .01f
        ((time + .03f) * 1000).toLong()
    }
}
