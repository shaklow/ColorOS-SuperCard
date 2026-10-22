package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class NativeCardsSurfaceTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun iconGlyphIsActuallyPaintedInsideItsTouchTarget() {
        val button = CardIconButton(RuntimeEnvironment.getApplication().let {
            androidx.appcompat.view.ContextThemeWrapper(it, R.style.Theme_DhuUi)
        })
        val side = (48 * button.resources.displayMetrics.density).toInt()
        button.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, side, side)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        var ink = 0
        for (y in side / 4 until side * 3 / 4) for (x in side / 4 until side * 3 / 4)
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 128) ink++
        assertTrue("Settings glyph must be visible, not merely clickable", ink > side)
        bitmap.recycle()
    }

    @Test fun changedOrEmptyCameraSelectionsRemoveStaleActions() {
        val root = NativeCardsSurface.create(RuntimeEnvironment.getApplication())
        NativeCardsSurface.renderLens(root, Bundle().apply {
            putStringArray("types", arrayOf("take_photo", "portrait")); putStringArray("titles", arrayOf("拍照", "人像"))
        })
        assertTrue(root.findViewWithTag<View>("native_lens_portrait") is MaterialButton)
        NativeCardsSurface.renderLens(root, Bundle())
        assertNull(root.findViewWithTag<View>("native_lens_portrait"))
        for (tag in listOf("settings", "lens_settings", "legacy"))
            assertTrue(root.findViewWithTag<View>("native_$tag") is MaterialButton)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun cardsFitNarrowLargeTextAndWideWindowsInBothThemes() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (widthDp in listOf(320, 412, 840)) {
                val app = RuntimeEnvironment.getApplication()
                val config = Configuration(app.resources.configuration).apply {
                    fontScale = if (widthDp == 320) 1.3f else 1f
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val context = app.createConfigurationContext(config)
                val root = NativeCardsSurface.create(context)
                root.findViewWithTag<FrameLayout>("native_campus").addView(DhuCardSurface.create(context), FrameLayout.LayoutParams(-1, -1))
                NativeCardsSurface.renderLens(root, Bundle().apply {
                    putStringArray("types", arrayOf("take_photo", "portrait", "video", "night_view", "master", "high_piexl"))
                    putStringArray("titles", arrayOf("拍照", "人像", "录像", "夜景", "大师模式", "高像素"))
                })
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((1400 * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTrue(descendants(root).all { it.width <= width })
                assertTrue(root.findViewWithTag<View>("native_settings").height >= 48 * density)
                assertTrue(root.findViewWithTag<View>("native_lens_take_photo").width > 0)
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    val target = File(directory, "cards-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$widthDp-${android.os.Build.VERSION.SDK_INT}.png")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
        }
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
