package dev.local.supercardui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class AnimatedOrderColumnTest {
    @Test fun reorderKeepsRowsAttachedAndReducedMotionSettlesImmediately() {
        val context = RuntimeEnvironment.getApplication()
        val column = AnimatedOrderColumn(context)
        val a = View(context).apply { minimumHeight = 48 }
        val b = View(context).apply { minimumHeight = 48 }
        column.reorder(listOf(a, b)); layout(column)
        assertTrue(a.top < b.top)
        val before = listOf(a.parent, b.parent)
        ReflectionHelpers.callStaticMethod<Void>(ValueAnimator::class.java, "setDurationScale", ReflectionHelpers.ClassParameter.from(java.lang.Float.TYPE, 0f))
        try {
            column.reorder(listOf(b, a)); layout(column)
            assertTrue(b.top < a.top)
            assertEquals(before, listOf(a.parent, b.parent)); assertSame(column, a.parent)
            assertEquals(0f, a.translationY, 0f); assertEquals(0f, b.translationY, 0f)
            assertEquals(b.id, a.accessibilityTraversalAfter)
            assertEquals(2, column.childCount)
        } finally {
            ReflectionHelpers.callStaticMethod<Void>(ValueAnimator::class.java, "setDurationScale", ReflectionHelpers.ClassParameter.from(java.lang.Float.TYPE, 1f))
        }
    }
    private fun layout(view: ViewGroup) {
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
