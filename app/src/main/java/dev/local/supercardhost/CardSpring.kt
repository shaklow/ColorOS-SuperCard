package dev.local.supercardhost

import kotlin.math.exp
import kotlin.math.abs

/** Original AnalyticSpringDriver, including its virtual-time to playback-time mapping. */
internal class CardSpring(from: Float, private val target: Float, velocity: Float, duration: Float) {
    private val omega = 9.06472f / duration.coerceAtLeast(.01f)
    private val a = from - target
    private val b = velocity + omega * a
    private val estimated = 9.232803f / omega
    val durationMillis = ((estimated * 1000 + 500).toLong()).coerceIn(16, 10_000)
    private val playbackSeconds = durationMillis / 1000f
    val clockRate = estimated / playbackSeconds
    private fun time(seconds: Float) = seconds.coerceIn(0f, playbackSeconds) * clockRate
    private fun value(t: Float) = target + (a + b * t) * exp(-omega * t)
    private fun settled(seconds: Float, value: Float) = seconds >= playbackSeconds ||
        (seconds > playbackSeconds * .1f && abs(value - target) < .2f)
    fun position(seconds: Float): Float {
        val value = value(time(seconds))
        return if (settled(seconds, value)) target else value
    }
    fun velocity(seconds: Float): Float {
        val t = time(seconds)
        return if (settled(seconds, value(t))) 0f
            else (b - omega * (a + b * t)) * exp(-omega * t) * clockRate
    }
}
