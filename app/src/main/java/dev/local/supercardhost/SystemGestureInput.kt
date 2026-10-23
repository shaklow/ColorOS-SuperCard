package dev.local.supercardhost

import android.content.Context
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.util.Log
import kotlin.math.abs
import kotlin.math.tan

/** Owns one platform input monitor. No vivo gesture controller, card, model or callback is needed. */
internal class SystemGestureInput(context: Context, private val events: Events) : AutoCloseable {
    interface Events {
        fun enabled(): Boolean
        fun visible(): Boolean
        fun policy(): GesturePolicy.Decision
        fun begin()
        fun drag(distance: Float)
        fun end(commit: Boolean, velocityX: Float)
    }
    private val monitor: Any
    private val receiver: SystemGestureReceiver
    private var tracker: VelocityTracker? = null
    private var decision: GesturePolicy.Decision? = null
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var closed = false
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/", "Landroid/hardware/input/")
        val manager = requireNotNull(context.getSystemService(Context.INPUT_SERVICE))
        monitor = requireNotNull(manager.javaClass.getMethod("monitorGestureInput", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, "SuperCardNativeGesture", 0))
        try {
            val channel = requireNotNull(monitor.javaClass.getMethod("getInputChannel").invoke(monitor))
            receiver = SystemGestureReceiver(channel, Looper.getMainLooper()) { event ->
                try { event is MotionEvent && touch(event) } catch (error: Throwable) {
                    try { cancel() } catch (cleanup: Throwable) { Log.w("SuperCardNativeInput", "Cannot cancel input", cleanup) }
                    Log.w("SuperCardNativeInput", "Gesture input failed", error); false
                }
            }
        } catch (error: Throwable) { monitor.javaClass.getMethod("dispose").invoke(monitor); throw error }
    }
    private fun touch(event: MotionEvent): Boolean {
        if (closed) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            if (!events.enabled() || events.visible() || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
            if ((event.javaClass.getMethod("getDisplayId").invoke(event) as Int) != 0) return false
            val next = events.policy()
            if (!next.region.contains(event.rawX, event.rawY)) return false
            decision = next; startX = event.rawX; startY = event.rawY
            tracker = VelocityTracker.obtain()
        }
        val active = decision ?: return false
        if (!events.enabled() || event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val claimed = dragging; cancel(); return claimed
        }
        tracker?.addMovement(event)
        val distance = startX - event.rawX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val threshold = (if (active.screen.locked) 12 else 8) * active.display.density
                    val y = abs(event.rawY - startY)
                    if (abs(distance) <= threshold && y <= threshold) return false
                    val slope = tan(Math.toRadians(if (active.screen.locked) 20.0 else 75.0)).toFloat()
                    if (distance <= 0 || y > distance * slope) { cancel(); return false }
                    // Claim once, after intent is known. The app underneath receives CANCEL.
                    monitor.javaClass.getMethod("pilferPointers").invoke(monitor)
                    dragging = true; events.begin()
                }
                events.drag(distance.coerceAtLeast(0f))
            }
            MotionEvent.ACTION_UP -> {
                val claimed = dragging
                if (claimed) {
                    tracker?.computeCurrentVelocity(1000, 20_000 * active.display.density)
                    val velocity = tracker?.xVelocity ?: 0f
                    dragging = false
                    events.end(CardMotionRules.edgeCommit(distance, velocity, active.screen.locked, active.display.density), velocity)
                }
                reset(); return claimed
            }
        }
        return dragging
    }
    fun cancel() { val claimed = dragging; dragging = false; reset(); if (claimed) events.end(false, 0f) }
    private fun reset() { decision = null; tracker?.recycle(); tracker = null }
    override fun close() {
        if (closed) return
        closed = true
        try { cancel() } finally { try { receiver.close() } finally { monitor.javaClass.getMethod("dispose").invoke(monitor) } }
    }
}
