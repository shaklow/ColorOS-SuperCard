package dev.local.supercardhost

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/** Intercepts confirmed horizontal drags only. ViewGroup delivers child CANCEL exactly once. */
internal class NativeGestureViewport(context: Context, private val events: Events) : FrameLayout(context) {
    interface Events {
        fun busy(): Boolean
        fun begin(): Boolean
        fun drag(distance: Float)
        fun end(distance: Float, velocity: Float, cancel: Boolean)
        fun queue(delta: Int)
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var rejected = false
    private var blocked = false
    private var suppress = false
    init { isClickable = true }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tracker?.recycle(); tracker = VelocityTracker.obtain()
            startX = event.rawX; startY = event.rawY; dragging = false; rejected = false; suppress = false
            blocked = events.busy()
            rejected = protectedAt(this, event.rawX.toInt(), event.rawY.toInt())
        }
        tracker?.addMovement(event)
        if (event.pointerCount > 1) {
            if (dragging) { dragging = false; events.end(0f, 0f, true); suppress = true }
            rejected = true
        }
        return try { super.dispatchTouchEvent(event) } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (dragging) { dragging = false; events.end(0f, 0f, true) }
                tracker?.recycle(); tracker = null
            }
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (blocked || suppress) return true
        if (dragging) return true
        if (rejected || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val x = event.rawX - startX; val y = event.rawY - startY
        if (abs(y) > slop && abs(y) >= abs(x)) { rejected = true; return false }
        if (abs(x) > slop && abs(x) > abs(y) * 1.2f) {
            if (scrollableAt(this, event.rawX.toInt(), event.rawY.toInt(), if (x > 0) -1 else 1)) { rejected = true; return false }
            dragging = events.begin()
            if (dragging) { parent?.requestDisallowInterceptTouchEvent(true); events.drag(x) }
            return dragging
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val distance = event.rawX - startX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!blocked && !suppress && !dragging) onInterceptTouchEvent(event)
                if (dragging) events.drag(distance)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                val velocity = tracker?.xVelocity ?: 0f
                if (dragging) { dragging = false; events.end(distance, velocity, event.actionMasked == MotionEvent.ACTION_CANCEL) }
                else if (blocked && !rejected && event.actionMasked == MotionEvent.ACTION_UP && abs(distance) > abs(event.rawY - startY)) {
                    events.queue(CardMotionRules.pageDelta(distance, velocity, width.toFloat(), resources.displayMetrics.density))
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun onDetachedFromWindow() {
        tracker?.recycle(); tracker = null; dragging = false
        super.onDetachedFromWindow()
    }
    private fun inside(view: View, x: Int, y: Int) = view.visibility == VISIBLE && view.getGlobalVisibleRect(Rect()) && run {
        val location = IntArray(2); view.getLocationOnScreen(location)
        x >= location[0] && x < location[0] + view.width && y >= location[1] && y < location[1] + view.height
    }
    private fun protectedAt(view: View, x: Int, y: Int): Boolean {
        if (!inside(view, x, y)) return false
        if (view is TextureView) return true
        return view is ViewGroup && (view.childCount - 1 downTo 0).any { protectedAt(view.getChildAt(it), x, y) }
    }
    private fun scrollableAt(view: View, x: Int, y: Int, direction: Int): Boolean {
        if (!inside(view, x, y)) return false
        if (view !== this && view.canScrollHorizontally(direction)) return true
        return view is ViewGroup && (view.childCount - 1 downTo 0).any { scrollableAt(view.getChildAt(it), x, y, direction) }
    }
}
