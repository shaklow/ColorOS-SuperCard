package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.shape.MaterialShapeDrawable
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
class PayCardAppearanceTest {
    @Test fun appearancePreservesOriginalViewOwnersAndCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val frame = ImageView(context); val logo = ImageView(context)
        val settings = ImageView(context); val hint = TextView(context)
        val display = FrameLayout(context).apply { tag = "display_owner_fixture" }
        listOf(frame, logo, settings, hint, display).forEach { root.addView(it) }
        var clicked = 0
        settings.setOnClickListener { clicked++ }
        val originalDisplayParams = display.layoutParams
        PayCardAppearance.style(context, "card", bindings(frame, logo, settings, hint))
        assertTrue(frame.background is LayerDrawable)
        assertTrue(settings.background is RippleDrawable)
        assertEquals(5, root.childCount)
        assertSame(root, settings.parent)
        assertSame(display, root.getChildAt(4))
        assertSame(originalDisplayParams, display.layoutParams)
        assertTrue(settings.performClick())
        assertEquals(1, clicked)
        assertEquals("PAY 设置", settings.contentDescription)
    }

    @Test fun existingTilesKeepGeometryTextIconsSelectionAndInput() {
        val context = RuntimeEnvironment.getApplication()
        val tile = FrameLayout(context)
        val selected = ImageView(context); val icon = ImageView(context); val label = TextView(context)
        val originalIcon = ColorDrawable(Color.GREEN); icon.setImageDrawable(originalIcon)
        label.text = "微信付款码"; label.textSize = 9f
        tile.addView(selected, FrameLayout.LayoutParams(76, 60))
        tile.addView(icon); tile.addView(label)
        tile.isSelected = true; selected.isSelected = true
        var clicks = 0
        tile.setOnClickListener { clicks++ }
        val params = selected.layoutParams; val size = label.textSize
        PayCardAppearance.style(context, "function", arrayOf(tile, selected, icon, label))
        assertSame(params, selected.layoutParams)
        assertEquals(76, params.width); assertEquals(60, params.height)
        assertSame(originalIcon, icon.drawable)
        assertEquals("微信付款码", label.text.toString())
        assertEquals(size, label.textSize, .01f)
        assertTrue(tile.isSelected && selected.isSelected)
        assertTrue(tile.performClick()); assertEquals(1, clicks)
        assertEquals(3, tile.childCount)
        val fill = (tile.background as MaterialShapeDrawable).fillColor!!
        assertNotEquals(fill.defaultColor, fill.getColorForState(intArrayOf(android.R.attr.state_selected), 0))
    }

    @Test fun invalidPartOrBindingsDoNotChangeOriginalViews() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context).apply { background = ColorDrawable(Color.RED) }
        val before = root.background
        assertThrows(IllegalArgumentException::class.java) { PayCardAppearance.style(context, "unknown", Array(4) { root }) }
        assertThrows(ClassCastException::class.java) { PayCardAppearance.style(context, "card", Array(6) { root }) }
        assertSame(before, root.background)
        assertEquals(0, root.childCount)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun settingsGlyphIsVisibleInBothThemes() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val context = app.createConfigurationContext(config)
            val settings = ImageView(context)
            PayCardAppearance.style(context, "card", bindings(ImageView(context), ImageView(context), settings, TextView(context)))
            val side = (48 * context.resources.displayMetrics.density + .5f).toInt()
            settings.measure(exact(side), exact(side)); settings.layout(0, 0, side, side)
            val image = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            settings.draw(Canvas(image))
            val background = image.getPixel(side / 8, side / 2)
            var glyph = 0
            for (y in side / 4 until side * 3 / 4) for (x in side / 4 until side * 3 / 4)
                if (ColorUtils.calculateContrast(image.getPixel(x, y), background) > 3) glyph++
            assertTrue("PAY settings gear must be painted with visible contrast", glyph > side)
            image.recycle()
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun settingsAndFunctionSurfacesRenderWithBothThemesAndFontScales() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (scale in listOf(1f, 1.3f, 1.6f)) {
                val app = RuntimeEnvironment.getApplication()
                val config = Configuration(app.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                    fontScale = scale
                }
                val context = app.createConfigurationContext(config)
                val density = context.resources.displayMetrics.density
                fun dp(value: Int) = (value * density + .5f).toInt()
                val root = FrameLayout(context)
                val shadow = View(context).apply {
                    background = GradientDrawable().apply { setColor(0xb3ffffff.toInt()); cornerRadius = dp(20).toFloat() }
                }
                root.addView(shadow, FrameLayout.LayoutParams(-1, -1))
                val frame = ImageView(context); root.addView(frame, FrameLayout.LayoutParams(-1, -1))
                val logo = ImageView(context).apply { setImageResource(android.R.drawable.ic_menu_view) }
                root.addView(logo, FrameLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(20); topMargin = dp(20) })
                val settings = ImageView(context)
                root.addView(settings, FrameLayout.LayoutParams(dp(54), dp(54), android.view.Gravity.TOP or android.view.Gravity.END))
                val hint = TextView(context).apply { text = "PAY"; textSize = 18f }
                root.addView(hint, FrameLayout.LayoutParams(-2, -2).apply { topMargin = dp(26); leftMargin = dp(68) })
                val actions = LinearLayout(context)
                root.addView(actions, FrameLayout.LayoutParams(-1, dp(190), android.view.Gravity.BOTTOM))
                PayCardAppearance.style(context, "card", arrayOf(frame, logo, settings, hint, shadow, actions))
                for ((index, title) in listOf("微信付款码", "支付宝付款码", "微信乘车码", "支付宝乘车码").withIndex()) {
                    val tile = FrameLayout(context)
                    root.addView(tile, FrameLayout.LayoutParams(dp(114), dp(72)).apply {
                        leftMargin = dp(20 + index % 2 * 126); topMargin = dp(260 + index / 2 * 84)
                    })
                    val background = ImageView(context); tile.addView(background, FrameLayout.LayoutParams(-1, -1))
                    val icon = ImageView(context).apply { setImageResource(android.R.drawable.ic_menu_view) }
                    tile.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
                    val label = TextView(context).apply { text = title; textSize = 9f; gravity = android.view.Gravity.CENTER }
                    tile.addView(label, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply { bottomMargin = dp(8) })
                    PayCardAppearance.style(context, "function", arrayOf(tile, background, icon, label))
                    val fill = (tile.background as MaterialShapeDrawable).fillColor!!.defaultColor
                    assertTrue("PAY labels must retain contrast", ColorUtils.calculateContrast(label.currentTextColor, fill) >= 4.5)
                }
                root.measure(exact(dp(280)), exact(dp(450))); root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertEquals(dp(48), settings.width); assertEquals(dp(48), settings.height)
                assertTrue(settings.right <= root.width && settings.bottom <= root.height)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                assertTransparentCorners(image, dp(4), dp(12))
                val surface = (shadow.background as MaterialShapeDrawable).fillColor!!.defaultColor
                var artwork = 0
                for (y in dp(90) until dp(240)) for (x in dp(80) until dp(200))
                    if (image.getPixel(x, y) != surface) artwork++
                assertTrue("PAY idle space must contain the wallet artwork", artwork > dp(40) * dp(40))
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val file = File(directory, "pay-appearance-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$scale-${android.os.Build.VERSION.SDK_INT}.png")
                    file.parentFile?.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                image.recycle()
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun legacyWhiteUnderlayCannotLeakAndArtworkFollowsIdleFrame() {
        val context = RuntimeEnvironment.getApplication()
        fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
        val root = FrameLayout(context)
        val shadow = View(context).apply {
            background = GradientDrawable().apply { setColor(0xb3ffffff.toInt()); cornerRadius = dp(20).toFloat() }
            alpha = .8f; elevation = dp(5).toFloat()
        }
        val frame = ImageView(context).apply { elevation = dp(5).toFloat() }
        val actions = LinearLayout(context)
        root.addView(shadow, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        root.addView(actions, FrameLayout.LayoutParams(-1, dp(140), android.view.Gravity.BOTTOM))
        root.measure(exact(dp(280)), exact(dp(450))); root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        fun draw() = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        val legacy = draw()
        assertTrue("Fixture must reproduce the exposed white underlay", Color.alpha(legacy.getPixel(dp(4), dp(12))) > 100)
        legacy.recycle()
        val views = arrayOf(frame, ImageView(context), ImageView(context), TextView(context), shadow, actions)
        repeat(3) { PayCardAppearance.style(context, "card", views) }
        assertEquals(3, root.childCount)
        assertEquals(.8f, shadow.alpha, .001f); assertEquals(dp(5).toFloat(), shadow.elevation, .001f)
        val idle = draw(); assertTransparentCorners(idle, dp(4), dp(12))
        val artX = dp(140); val artY = dp(180)
        val artColor = idle.getPixel(artX, artY)
        frame.visibility = View.INVISIBLE
        val payment = draw()
        assertNotEquals("Artwork must disappear with the OEM idle frame", artColor, payment.getPixel(artX, artY))
        assertTransparentCorners(payment, dp(4), dp(12))
        frame.visibility = View.VISIBLE
        frame.alpha = 0f
        val faded = draw()
        assertEquals(payment.getPixel(artX, artY), faded.getPixel(artX, artY))
        frame.alpha = 1f
        actions.layout(0, dp(100), dp(280), dp(450))
        val crowded = draw()
        val surface = ((frame.background as LayerDrawable).getDrawable(0) as MaterialShapeDrawable).fillColor!!.defaultColor
        assertEquals("Do not paint over the bill or shortcut area", surface, crowded.getPixel(artX, artY))
        listOf(idle, payment, faded, crowded).forEach { it.recycle() }
    }

    private fun bindings(frame: ImageView, logo: ImageView, settings: ImageView, hint: TextView): Array<View> =
        arrayOf(frame, logo, settings, hint, View(frame.context), LinearLayout(frame.context))

    private fun assertTransparentCorners(image: Bitmap, x: Int, y: Int) {
        for ((px, py) in listOf(x to y, image.width - 1 - x to y,
            x to image.height - 1 - y, image.width - 1 - x to image.height - 1 - y))
            assertEquals("No legacy fill outside the PAY rounded surface at $px,$py", 0, Color.alpha(image.getPixel(px, py)))
    }

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
}
