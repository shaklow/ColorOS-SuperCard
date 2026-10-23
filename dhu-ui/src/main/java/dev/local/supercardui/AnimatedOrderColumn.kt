package dev.local.supercardui

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Keeps rows attached while their layout order changes, animating from their current visual positions. */
internal class AnimatedOrderColumn(context: Context) : ViewGroup(context) {
    private var rows = emptyList<View>()
    private var previous: Map<View, Float>? = null
    private val gap = (12 * resources.displayMetrics.density + .5f).toInt()

    fun reorder(next: List<View>) {
        if (next == rows) return
        previous = if (isLaidOut && rows.isNotEmpty() && ValueAnimator.areAnimatorsEnabled())
            rows.associateWith { it.top + it.translationY } else null
        rows.filter { it !in next }.forEach { it.animate().cancel(); removeView(it) }
        next.filter { it.parent == null }.forEach { addView(it, LayoutParams(-1, -2)) }
        rows = next.toList()
        rows.forEachIndexed { index, view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            view.accessibilityTraversalAfter = rows.getOrNull(index - 1)?.id ?: View.NO_ID
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var height = paddingTop + paddingBottom
        rows.forEach { view ->
            measureChild(view, widthMeasureSpec, 0)
            height += view.measuredHeight + gap
        }
        setMeasuredDimension(resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val before = previous; previous = null
        var y = paddingTop
        rows.forEach { view ->
            val visualY = before?.get(view)
            view.layout(paddingLeft, y, width - paddingRight, y + view.measuredHeight)
            if (before != null) {
                view.animate().cancel()
                view.translationY = (visualY ?: y.toFloat()) - y
                if (view.translationY != 0f) view.animate().translationY(0f).setDuration(260)
                    .setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f)).start()
            } else if (!ValueAnimator.areAnimatorsEnabled()) {
                view.animate().cancel(); view.translationY = 0f
            }
            y += view.measuredHeight + gap
        }
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        rows.forEach { it.addFocusables(views, direction, focusableMode) }
    }

    override fun onDetachedFromWindow() {
        rows.forEach { it.animate().cancel(); it.translationY = 0f; it.alpha = 1f }
        previous = null
        super.onDetachedFromWindow()
    }
}
