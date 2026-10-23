package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class NativeCardSurfaceTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun independentSurfaceFitsBothThemesAndKeepsNavigationAccessible() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) for (width in listOf(280, 360)) {
            val app = RuntimeEnvironment.getApplication()
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                fontScale = 1.3f; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            val root = NativeCardSurface.create(context); val events = mutableListOf<String>()
            NativeCardSurface.bind(root, Consumer { events.add(it.getString("action")!!) })
            val content = root.findViewWithTag<FrameLayout>("native_content")
            content.addView(TextView(context).apply { text = "卡片内容"; setPadding(24, 24, 24, 24) }, FrameLayout.LayoutParams(-1, -1))
            val state = Bundle().apply { putInt("index", 0); putInt("count", 2); putString("title", "LENS") }
            NativeCardSurface.render(root, state); assertTrue(events.isEmpty())
            assertNull(root.findViewWithTag<View>("native_previous")); assertNull(root.findViewWithTag<View>("native_next"))
            assertFalse(root.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
            assertTrue(root.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(listOf("next"), events)
            state.putInt("index", 1); NativeCardSurface.render(root, state)
            assertSame(content, root.findViewWithTag("native_content"))
            assertTrue(root.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
            assertFalse(root.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertTrue(root.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null))
            assertEquals(listOf("next", "previous", "close"), events)
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt(); val h = (450 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            assertEquals(h, content.height)
            System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); root.draw(Canvas(image))
                val file = File(directory, "native-host-$width-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${android.os.Build.VERSION.SDK_INT}.png")
                file.parentFile?.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
        }
    }
}
