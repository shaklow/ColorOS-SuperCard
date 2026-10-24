package dev.local.supercardhost

import android.app.Activity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class NativeGestureViewportTest {
    private class Events : NativeGestureViewport.Events {
        var busy = false; var starts = 0
        var interrupt = false; var content: View? = null
        var protect = true
        val ends = mutableListOf<Boolean>(); val queued = mutableListOf<Int>()
        override fun busy() = busy
        override fun begin(): Boolean { starts++; return true }
        override fun drag(distance: Float) = Unit
        override fun end(distance: Float, velocity: Float, cancel: Boolean) { ends.add(cancel) }
        override fun queue(delta: Int) { queued.add(delta) }
        override fun interrupt() = interrupt
        override fun content() = content
        override fun protectTexture() = protect
    }
    private fun scenario(texture: Boolean = false, busy: Boolean = false, test: (NativeGestureViewport, Events, MutableList<Int>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get(); val events = Events().apply { this.busy = busy }
            val root = NativeGestureViewport(activity, events); val actions = mutableListOf<Int>()
            val child = if (texture) object : TextureView(activity) {
                override fun onTouchEvent(event: MotionEvent): Boolean { actions.add(event.actionMasked); return true }
            } else object : View(activity) {
                override fun onTouchEvent(event: MotionEvent): Boolean { actions.add(event.actionMasked); return true }
            }
            root.addView(child, FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 280, 450)
            test(root, events, actions)
        } finally { controller.pause().stop().destroy() }
    }
    private fun send(root: View, action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(1, time, action, x, y, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun horizontalCaptureCancelsChildOnceAndDoesNotClickIt() = scenario { root, events, actions ->
        send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
        send(root, MotionEvent.ACTION_MOVE, 140f, 210f, 200)
        send(root, MotionEvent.ACTION_MOVE, 30f, 210f, 300)
        send(root, MotionEvent.ACTION_UP, 30f, 210f, 400)
        assertEquals(1, events.starts); assertEquals(listOf(false), events.ends)
        assertEquals(1, actions.count { it == MotionEvent.ACTION_CANCEL })
        assertFalse(actions.contains(MotionEvent.ACTION_UP))
    }
    @Test fun newDownCanImmediatelyTakeOverAnInFlightPage() = scenario { root, events, actions ->
        events.interrupt = true
        send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
        assertEquals(1, events.starts)
        send(root, MotionEvent.ACTION_MOVE, 30f, 210f, 200)
        send(root, MotionEvent.ACTION_UP, 30f, 210f, 300)
        assertEquals(listOf(false), events.ends)
        assertTrue(events.queued.isEmpty()); assertTrue(actions.isEmpty())
    }
    @Test fun protectedPaymentPreviewStillAllowsCardBodySwiping() = scenario(texture = true) { root, events, actions ->
        events.protect = false
        send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
        send(root, MotionEvent.ACTION_MOVE, 30f, 210f, 200)
        send(root, MotionEvent.ACTION_UP, 30f, 210f, 300)
        assertEquals(1, events.starts)
        assertEquals(1, actions.count { it == MotionEvent.ACTION_CANCEL })
        assertFalse(actions.contains(MotionEvent.ACTION_UP))
    }
    @Test fun textureInAnInactiveRearCardDoesNotBlockTheActualFrontCard() = scenario { root, events, _ ->
        events.content = root.getChildAt(0)
        root.addView(TextureView(root.context), 0, FrameLayout.LayoutParams(280, 450))
        root.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 280, 450)
        send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
        send(root, MotionEvent.ACTION_MOVE, 30f, 210f, 200)
        send(root, MotionEvent.ACTION_UP, 30f, 210f, 300)
        assertEquals(1, events.starts)
    }
    @Test fun verticalScrollAndCameraTextureKeepTheirOwnTouchStreams() {
        scenario { root, events, actions ->
            send(root, MotionEvent.ACTION_DOWN, 220f, 100f, 100)
            send(root, MotionEvent.ACTION_MOVE, 215f, 210f, 200)
            send(root, MotionEvent.ACTION_MOVE, 30f, 270f, 300)
            send(root, MotionEvent.ACTION_UP, 30f, 270f, 400)
            assertEquals(0, events.starts); assertTrue(actions.contains(MotionEvent.ACTION_UP))
        }
        scenario(texture = true) { root, events, actions ->
            send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
            send(root, MotionEvent.ACTION_MOVE, 30f, 210f, 200)
            send(root, MotionEvent.ACTION_UP, 30f, 210f, 300)
            assertEquals(0, events.starts); assertTrue(actions.contains(MotionEvent.ACTION_UP))
            assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
        }
    }
    @Test fun multitouchCancelsOwnedDragAndCannotCommitFromTheRemainingPointer() = scenario { root, events, actions ->
        send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
        send(root, MotionEvent.ACTION_MOVE, 150f, 210f, 200)
        val properties = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(2) { MotionEvent.PointerCoords().apply { x = 130f + it * 20; y = 210f; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(1, 250, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        send(root, MotionEvent.ACTION_UP, 30f, 210f, 400)
        assertEquals(1, events.starts); assertEquals(listOf(true), events.ends)
        assertEquals(1, actions.count { it == MotionEvent.ACTION_CANCEL })
    }
    @Test fun swipeDuringSettlingQueuesNavigationAndDoesNotInterruptTheVisibleAnimation() = scenario(busy = true) { root, events, actions ->
        send(root, MotionEvent.ACTION_DOWN, 230f, 210f, 100)
        send(root, MotionEvent.ACTION_MOVE, 30f, 210f, 200)
        send(root, MotionEvent.ACTION_UP, 30f, 210f, 300)
        assertEquals(0, events.starts); assertTrue(events.ends.isEmpty())
        assertEquals(listOf(1), events.queued); assertTrue(actions.isEmpty())
    }
}
