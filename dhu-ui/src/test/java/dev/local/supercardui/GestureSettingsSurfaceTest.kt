package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
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
class GestureSettingsSurfaceTest {
    private fun values() = Bundle().apply {
        putBoolean("loaded", true); putBoolean("connected", true); putBoolean("enabled", true)
        putFloat("top", .76f); putFloat("bottom", .98f); putFloat("handleOpacity", .4f)
        putInt("position", 974); putInt("length", 187); putInt("opacity", 400); putInt("lockStyle", 2)
        putBoolean("handleVisible", true); putBoolean("handleAutoHide", true); putBoolean("unlocked", true)
        putString("message", "调整后自动保存")
    }
    @Test fun renderingDoesNotEmitWritesAndPendingSaveDoesNotDisableOrResetControls() {
        val root = GestureSettingsSurface.create(RuntimeEnvironment.getApplication()); val events = mutableListOf<Bundle>()
        GestureSettingsSurface.bind(root, Consumer { events.add(it) }); GestureSettingsSurface.render(root, values())
        assertTrue(events.isEmpty())
        val toggle = root.findViewWithTag<MaterialSwitch>("gesture_unlocked")
        toggle.performClick(); assertEquals("unlocked", events.single().getString("action")); assertFalse(events.single().getBoolean("value"))
        GestureSettingsSurface.render(root, values().apply { putBoolean("unlocked", false); putBoolean("saving", true) })
        assertFalse(toggle.isChecked); assertTrue(toggle.isEnabled); assertEquals(1, events.size)
        GestureSettingsSurface.render(root, values().apply { putBoolean("handleVisible", false) })
        assertFalse(root.findViewWithTag<Slider>("gesture_opacity").isEnabled)
        assertFalse(root.findViewWithTag<MaterialSwitch>("gesture_handleAutoHide").isEnabled)
        assertTrue(root.findViewWithTag<Slider>("gesture_length").isEnabled)
        GestureSettingsSurface.render(root, Bundle())
        assertFalse(toggle.isEnabled); assertFalse(root.findViewWithTag<View>("gesture_preview").isEnabled)
    }
    @Test fun previewSupportsTouchAndAccessiblePositionChangesWithoutWritingOnRender() {
        val root = GestureSettingsSurface.create(RuntimeEnvironment.getApplication()); val events = mutableListOf<Bundle>()
        GestureSettingsSurface.bind(root, Consumer { events.add(it) }); GestureSettingsSurface.render(root, values())
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 320, 740)
        val preview = root.findViewWithTag<View>("gesture_preview")
        assertTrue(preview.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(listOf("range", "commit"), events.map { it.getString("action") })
        assertEquals(.74f, events[0].getFloat("top"), .0001f); events.clear()
        val density = preview.resources.displayMetrics.density
        val x = preview.width / 2f + 58 * density
        val y = 10 * density + (preview.height - 20 * density) * .85f
        for ((action, point) in listOf(MotionEvent.ACTION_DOWN to y, MotionEvent.ACTION_MOVE to y - 30 * density, MotionEvent.ACTION_UP to y - 30 * density)) {
            val event = MotionEvent.obtain(0, 0, action, x, point, 0)
            assertTrue(preview.dispatchTouchEvent(event)); event.recycle()
        }
        assertEquals(listOf("tracking", "range", "commit"), events.map { it.getString("action") })
        assertTrue(events[1].getFloat("top") < .76f)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun layoutsFitLargeTextInBothThemesAndWidths() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) for (widthDp in listOf(320, 600)) {
            val app = RuntimeEnvironment.getApplication()
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                fontScale = 1.3f; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            val root = GestureSettingsSurface.create(context); GestureSettingsSurface.render(root, values())
            val density = context.resources.displayMetrics.density; val width = (widthDp * density).toInt(); val height = (740 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height)
            for (name in listOf("position", "length", "opacity", "unlocked", "handleVisible", "handleAutoHide", "lock", "reset")) {
                val view = root.findViewWithTag<View>("gesture_$name")
                assertTrue("$name height", view.height >= 48 * density); assertTrue("$name width", view.width <= width)
            }
            System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                val suffix = "$widthDp-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${android.os.Build.VERSION.SDK_INT}"
                save(root, File(directory, "gestures-$suffix.png"))
                val scroll = root.findViewWithTag<ScrollView>("gesture_scroll")
                scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)); save(root, File(directory, "gestures-bottom-$suffix.png"))
            }
        }
    }
    private fun save(root: View, file: File) {
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(image))
        file.parentFile?.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
