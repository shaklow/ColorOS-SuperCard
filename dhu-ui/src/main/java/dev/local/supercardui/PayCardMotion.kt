package dev.local.supercardui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView

/** OEM PAY motion: shortcuts gather into tabs in 350ms; the page fades in from 50–400ms. */
internal class PayCardMotion(private val root: ViewGroup, private val idle: ViewGroup,
    private val opened: View, private val shortcuts: View) {
    private val position = PathInterpolator(.25f, .1f, .25f, 1f)
    private val opacity = PathInterpolator(.33f, 0f, .67f, 1f)
    private var running: ValueAnimator? = null
    private var cells = emptyList<View>()
    private var destinations = emptyList<View>()
    private class Move(val cell: View, val x: Float, val y: Float, val children: List<View>)

    fun cancel(showOpened: Boolean) {
        running?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
        running = null
        cells.forEach { cell ->
            cell.translationX = 0f; cell.translationY = 0f
            cell.background?.alpha = 255
            descendants(cell).forEach { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
        }
        cells = emptyList()
        destinations.forEach { it.alpha = 1f }; destinations = emptyList()
        for (i in 0 until idle.childCount) idle.getChildAt(i).alpha = 1f
        idle.alpha = 1f; opened.alpha = 1f
        idle.visibility = if (showOpened) View.GONE else View.VISIBLE
        opened.visibility = if (showOpened) View.VISIBLE else View.INVISIBLE
        opened.bringToFront()
    }
    fun start(enter: Boolean, pairs: List<Pair<View, View>>): ValueAnimator {
        cancel(!enter)
        cells = pairs.map { it.first }
        destinations = pairs.map { it.second }
        idle.visibility = View.VISIBLE; opened.visibility = View.VISIBLE
        if (enter) idle.bringToFront() else opened.bringToFront()
        opened.alpha = if (enter) 0f else 1f
        idle.alpha = if (enter) 1f else 0f
        val moves = pairs.map { (source, target) ->
            val from = bounds(source); val to = bounds(target)
            source.background?.mutate()
            Move(source, to.exactCenterX() - from.exactCenterX(), to.exactCenterY() - from.exactCenterY(), descendants(source))
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (enter) 400 else 200
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (enter) {
                    val p = position.getInterpolation((animation.currentPlayTime / 350f).coerceIn(0f, 1f))
                    val a = opacity.getInterpolation(((animation.currentPlayTime - 50) / 350f).coerceIn(0f, 1f))
                    opened.alpha = a
                    destinations.forEach { it.alpha = if (animation.currentPlayTime < 350) 0f else 1f }
                    for (i in 0 until idle.childCount) idle.getChildAt(i).let { if (it !== shortcuts) it.alpha = 1 - a }
                    moves.forEach { move ->
                        val cell = move.cell
                        cell.translationX = move.x * p; cell.translationY = move.y * p
                        cell.background?.alpha = (255 * (1 - p)).toInt()
                        move.children.forEach {
                            when (it) {
                                is TextView -> it.alpha = 1 - p
                                is ImageView -> { it.scaleX = 1 - p * (1 - .7777778f); it.scaleY = it.scaleX; it.alpha = 1 - a }
                            }
                        }
                    }
                } else {
                    val a = opacity.getInterpolation(animation.animatedFraction)
                    opened.alpha = 1 - a; idle.alpha = a
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (running === animation) cancel(enter) }
            })
        }
        running = animator; animator.start()
        return animator
    }
    private fun bounds(view: View) = Rect(0, 0, view.width, view.height).apply { root.offsetDescendantRectToMyCoords(view, this) }
    private fun descendants(view: View): List<View> = if (view is ViewGroup)
        (0 until view.childCount).flatMap { listOf(view.getChildAt(it)) + descendants(view.getChildAt(it)) } else emptyList()
}
