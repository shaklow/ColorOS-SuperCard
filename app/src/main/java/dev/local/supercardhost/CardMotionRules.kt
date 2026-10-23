package dev.local.supercardhost

import kotlin.math.abs

/** Distance/velocity rules use dp, matching the original edge gesture's 120/150dp and 300dp/s. */
internal object CardMotionRules {
    fun edgeCommit(distance: Float, velocityX: Float, locked: Boolean, density: Float): Boolean {
        if (!distance.isFinite() || !velocityX.isFinite() || !density.isFinite() || density <= 0) return false
        return if (abs(velocityX) > 300 * density) velocityX < 0
            else distance > (if (locked) 150 else 120) * density
    }
    fun pageDelta(distance: Float, velocityX: Float, width: Float, density: Float): Int {
        if (!distance.isFinite() || !velocityX.isFinite() || !width.isFinite() || !density.isFinite() || width <= 0 || density <= 0) return 0
        val committed = if (abs(velocityX) > 500 * density) distance * velocityX > 0
            else abs(distance) > minOf(width * .25f, 80 * density)
        return if (!committed || distance == 0f) 0 else if (distance < 0) 1 else -1
    }
    fun duration(distance: Float, velocity: Float, width: Float): Long {
        if (!width.isFinite() || width <= 0 || !distance.isFinite() || !velocity.isFinite()) return 240
        val fraction = (abs(distance) / width).coerceIn(0f, 1f)
        val speedTime = if (abs(velocity) > 1f) abs(distance / velocity) * 1000 else 320f
        return minOf(140 + fraction * 180, speedTime.coerceAtLeast(140f)).toLong().coerceIn(140, 320)
    }
}
