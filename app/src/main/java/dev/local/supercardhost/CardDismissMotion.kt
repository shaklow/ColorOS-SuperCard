package dev.local.supercardhost

import kotlin.math.*

/** Original parallel dismissal: scroll to the first page while the stack leaves to the right. */
internal class CardDismissMotion(private val from: Float, private val target: Float,
    private val startScroll: Float, density: Float, velocity: Float = 0f) {
    private val duration = if (startScroll > 1f) .65f else .45f
    private val omega = (2 * Math.PI / ln(2.0)).toFloat() / duration
    private val estimated = 9.232803f / omega
    val durationMillis = (estimated * 1000).toLong()
    private fun time(seconds: Float) = seconds.coerceIn(0f, durationMillis / 1000f) * estimated / (durationMillis / 1000f)
    // LinearLayoutManager's default LinearSmoothScroller: 25ms/inch, then decelerate.
    val rollbackMillis = ceil(ceil(abs(startScroll) * 25 / (160 * density)) / .3356f).toLong()
    private val a = from - target
    private val b = velocity + omega * a
    fun progress(seconds: Float): Float {
        if (seconds * 1000 >= durationMillis) return 1f
        val t = time(seconds)
        return (1 - (1 + omega * t) * exp(-omega * t)).coerceIn(0f, 1f)
    }
    fun translation(seconds: Float): Float {
        if (seconds * 1000 >= durationMillis) return target
        val t = time(seconds)
        return target + (a + b * t) * exp(-omega * t)
    }
    fun scroll(seconds: Float): Float {
        if (rollbackMillis == 0L) return 0f
        val remaining = 1 - (seconds * 1000 / rollbackMillis).coerceIn(0f, 1f)
        return startScroll * remaining * remaining
    }
}
