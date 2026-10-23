package dev.local.supercardhost

import kotlin.math.abs

/** Distance/velocity rules use dp, matching the original edge gesture's 120/150dp and 300dp/s. */
internal object CardMotionRules {
    data class Pose(val x: Float, val scale: Float)
    data class PageFrame(val outgoing: Pose, val incoming: Pose)
    fun pageFrame(distance: Float, width: Float, direction: Int, density: Float): PageFrame {
        require(distance.isFinite() && width.isFinite() && width > 0 && density.isFinite() && density > 0)
        require(direction == 1 || direction == -1)
        val progress = (abs(distance) / width).coerceIn(0f, 1f)
        val outgoing = Pose(if (direction > 0) distance else 12 * density * progress, 1f - .04f * progress)
        val incoming = if (direction > 0) Pose(12 * density * (1f - progress), .96f + .04f * progress)
            else Pose(distance - width, 1f)
        return PageFrame(outgoing, incoming)
    }
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
