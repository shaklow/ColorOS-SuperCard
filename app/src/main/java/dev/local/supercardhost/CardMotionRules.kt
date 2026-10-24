package dev.local.supercardhost

import kotlin.math.abs

/** Distance/velocity rules use dp, matching the original edge gesture's 120/150dp and 300dp/s. */
internal object CardMotionRules {
    fun dragScroll(start: Float, distance: Float, last: Float): Float =
        (start - distance).coerceIn(minOf(0f, start), maxOf(last, start))
    data class StackFrame(val x: Float, val scale: Float, val z: Float, val shade: Float, val elevation: Float)
    /** Natural page offset plus the original StackPageTransformer. */
    fun stackFrame(position: Float, width: Float, density: Float): StackFrame {
        require(position.isFinite() && width > 0 && density > 0)
        if (position < 0f) return StackFrame((width + 20 * density) * position, 1f, 0f, 0f,
            ((position + 1f) * 20 * density).coerceIn(0f, 20 * density))
        val depth = position.coerceAtMost(5f)
        val shade = when {
            depth < 1 -> .12f * depth
            depth < 2 -> .12f + .08f * (depth - 1)
            depth < 3 -> .2f + .1f * (depth - 2)
            else -> .3f
        }
        return StackFrame((13.6f * density + width * .025f) * depth, 1 - .05f * depth,
            -8 * density * depth, shade, ((1 - depth) * 20 * density).coerceIn(0f, 20 * density))
    }
    data class Pose(val x: Float, val scale: Float)
    data class PageFrame(val outgoing: Pose, val incoming: Pose)
    fun pageFrame(distance: Float, width: Float, direction: Int, density: Float, exit: Float = width): PageFrame {
        require(distance.isFinite() && width.isFinite() && width > 0 && density.isFinite() && density > 0)
        require(direction == 1 || direction == -1)
        require(exit.isFinite() && exit >= width)
        val progress = (-direction * distance / width).coerceIn(-.08f, 1.08f)
        // OEM rear card: 5% smaller, with a visible 13.6dp edge beyond the front card.
        val peek = 13.6f * density + width * .025f
        val outgoing = Pose(if (direction > 0) -exit * progress else peek * progress,
            if (direction > 0) 1f else 1f - .05f * progress)
        val incoming = if (direction > 0) Pose(peek * (1f - progress), .95f + .05f * progress)
            else Pose(-exit * (1f - progress), 1f)
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
