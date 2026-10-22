package dev.local.supercardhost

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real host View lifecycle with the archive-unavailable fallback; no credential fetching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DhuPayViewLifecycleTest {
    @Test fun repeatedMountsUseFreshSessionsAndKeepOneSurface() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity); activity.setContentView(root)
            shadowOf(Looper.getMainLooper()).idle()
            val view = DhuPayView(activity, false); view.setActive(false)
            val scope = field(view, "lifecycle") as FloatingCardScope
            var previous: FloatingCardScope.Token? = null
            repeat(3) {
                root.addView(view)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(scope.isAttached); assertNotSame(previous, scope.token())
                previous = scope.token(); assertEquals(1, view.childCount)
                root.removeView(view)
                assertFalse(scope.isAttached)
                assertFalse(scope.postFor(previous, Runnable { fail("detached campus result") }))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun detachingClearsDisplayedContentEvenForTheFallbackSurface() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity); activity.setContentView(root)
            shadowOf(Looper.getMainLooper()).idle()
            val view = DhuPayView(activity, false); view.setActive(false); root.addView(view)
            val ui = field(view, "ui") as DhuMaterialUi.Surface
            set(view, "code", "noncredential-fixture")
            ui.qr.setImageDrawable(ColorDrawable(Color.RED)); ui.barcode.setImageDrawable(ColorDrawable(Color.BLUE))
            ui.codes.visibility = View.VISIBLE; ui.countdown.visibility = View.VISIBLE
            root.removeView(view)
            assertNull(field(view, "code")); assertNull(ui.qr.drawable); assertNull(ui.barcode.drawable)
            assertEquals(View.INVISIBLE, ui.codes.visibility); assertEquals(View.GONE, ui.countdown.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun failedConfigurationKeepsTheClearedOldSurfaceAndSession() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val context = object : ContextWrapper(activity) {
                override fun createConfigurationContext(overrideConfiguration: Configuration) =
                    throw IllegalStateException("configuration fixture")
            }
            val root = FrameLayout(activity); activity.setContentView(root)
            shadowOf(Looper.getMainLooper()).idle()
            val view = DhuPayView(context, false); view.setActive(false); root.addView(view)
            val before = view.getChildAt(0)
            val scope = field(view, "lifecycle") as FloatingCardScope; val token = scope.token()
            set(view, "code", "noncredential-fixture")
            view.dispatchConfigurationChanged(Configuration(activity.resources.configuration))
            assertSame(before, view.getChildAt(0)); assertEquals(1, view.childCount)
            assertSame(token, scope.token()); assertNull(field(view, "code"))
            root.removeView(view)
        } finally { controller.pause().stop().destroy() }
    }

    private fun field(view: DhuPayView, name: String): Any? = DhuPayView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(view)
    private fun set(view: DhuPayView, name: String, value: Any) = DhuPayView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(view, value)
}
