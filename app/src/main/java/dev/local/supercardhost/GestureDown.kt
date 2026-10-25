package dev.local.supercardhost

import kotlin.math.abs

/** ColorOS may replay the original DOWN after pilfering/focusing the new card window. */
internal data class GestureDown(val device: Int, val source: Int, val display: Int, val pointer: Int,
    val downTime: Long, val eventTime: Long, val x: Float, val y: Float) {
    fun isReplayOf(original: GestureDown) = device == original.device && source == original.source &&
        display == original.display && pointer == original.pointer &&
        abs(eventTime - original.eventTime) <= 1 && abs(downTime - original.downTime) <= 1 &&
        abs(x - original.x) < 1f && abs(y - original.y) < 1f
}
