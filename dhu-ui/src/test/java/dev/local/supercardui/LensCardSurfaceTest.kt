package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
class LensCardSurfaceTest {
    private fun selection() = Bundle().apply {
        putStringArray("types", arrayOf("take_photo", "portrait", "video", "night_view", "master", "high_piexl"))
        putStringArray("titles", arrayOf("拍照", "人像", "录像", "夜景", "大师模式", "高像素"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun iconGlyphIsActuallyPaintedInsideItsTouchTarget() {
        val button = CardIconButton(androidx.appcompat.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DhuUi))
        val side = (48 * button.resources.displayMetrics.density).toInt()
        button.measure(exact(side), exact(side)); button.layout(0, 0, side, side)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        var ink = 0
        for (y in side / 4 until side * 3 / 4) for (x in side / 4 until side * 3 / 4)
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 128) ink++
        assertTrue("Settings glyph must be visible, not merely clickable", ink > side)
        bitmap.recycle()
    }

    @Test fun changedOrEmptySelectionsRemoveStaleActions() {
        val root = LensCardSurface.create(RuntimeEnvironment.getApplication())
        assertTrue(root is MaterialCardView)
        LensCardSurface.render(root, selection())
        assertTrue(root.findViewWithTag<View>("lens_mode_portrait") is MaterialButton)
        val grid = root.findViewWithTag<GridLayout>("lens_modes")
        assertEquals("lens_mode_take_photo", grid.getChildAt(0).tag)
        LensCardSurface.render(root, Bundle())
        assertNull(root.findViewWithTag<View>("lens_mode_portrait"))
        assertEquals(0, grid.childCount)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("lens_empty").visibility)
        assertTrue(root.findViewWithTag<View>("lens_settings") is CardIconButton)
        assertFalse(descendants(root).any { (it.tag as? String)?.contains("refresh") == true })
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fitsOriginalFloatingCardBoundsWithBothThemesAndLargerText() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (scale in listOf(1f, 1.3f, 1.6f)) {
                val app = RuntimeEnvironment.getApplication()
                val config = Configuration(app.resources.configuration).apply {
                    fontScale = scale; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val context = app.createConfigurationContext(config)
                val root = LensCardSurface.create(context) as ViewGroup
                LensCardSurface.render(root, selection())
                val density = context.resources.displayMetrics.density
                root.measure(exact((280 * density).toInt()), exact((450 * density).toInt()))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val gear = root.findViewWithTag<View>("lens_settings")
                val rect = Rect(0, 0, gear.width, gear.height)
                root.offsetDescendantRectToMyCoords(gear, rect)
                assertTrue("48dp settings touch target", gear.height >= 48 * density && gear.width >= 48 * density)
                assertTrue("Settings stays inside floating card", Rect(0, 0, root.width, root.height).contains(rect))
                val grid = root.findViewWithTag<GridLayout>("lens_modes")
                assertEquals(if (scale <= 1.3f) 2 else 1, grid.columnCount)
                for (index in 0 until grid.childCount) {
                    val button = grid.getChildAt(index) as TextView
                    assertTrue("Camera action fits card width", button.width > 0 && button.width <= root.width)
                    assertTrue("Camera title does not clip", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
                val scroll = descendants(root).filterIsInstance<ScrollView>().single()
                if (scale <= 1.3f) assertTrue("Six default modes are available without page navigation or scrolling",
                    scroll.getChildAt(0).height <= scroll.height)
                else {
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    assertTrue("Very large text scrolls inside the same card", scroll.scrollY > 0)
                }
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    val file = File(directory, "floating-lens-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$scale-${android.os.Build.VERSION.SDK_INT}.png")
                    file.parentFile?.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
