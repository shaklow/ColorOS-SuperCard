package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
class PayCardSurfaceTest {
    private fun selection() = Bundle().apply {
        putStringArray("types", arrayOf("wx_scan", "alipay_scan", "wx_pay", "alipay_pay", "wx2_scan", "wx2_pay"))
        putStringArray("titles", arrayOf("微信扫一扫", "支付宝扫一扫", "微信付款码", "支付宝付款码", "微信分身扫一扫", "微信分身付款码"))
    }
    @Test fun callbacksUseFunctionIdentifiersAndRenderingKeepsTheEmbeddedOwner() {
        val root = PayCardSurface.create(RuntimeEnvironment.getApplication())
        val actions = mutableListOf<Bundle>()
        PayCardSurface.bind(root, Consumer { actions.add(it) })
        PayCardSurface.render(root, selection())
        root.findViewWithTag<View>("pay_function_wx_pay").performClick()
        assertEquals("function", actions.single().getString("action"))
        assertEquals("wx_pay", actions.single().getString("function"))
        val viewport = root.findViewWithTag<FrameLayout>("pay_viewport")
        val content = View(root.context); viewport.addView(content)
        PayCardSurface.render(root, selection().apply { putString("selected", "wx_pay"); putBoolean("loading", true) })
        assertSame(viewport, content.parent)
        assertTrue(root.findViewWithTag<View>("pay_tab_wx_pay").isSelected)
        PayCardSurface.render(root, selection().apply { putString("selected", "alipay_pay") })
        assertSame(viewport, content.parent)
        root.findViewWithTag<View>("pay_fullscreen").performClick()
        assertEquals("fullscreen", actions.last().getString("action"))
        PayCardSurface.render(root, Bundle())
        assertNull(root.findViewWithTag<View>("pay_function_wx_pay"))
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("pay_idle").visibility)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun originalViewportSettingsAndLabelsFitBothThemesAndLargerText() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) for (scale in listOf(1f, 1.3f, 1.6f)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply { fontScale = scale; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
            val root = PayCardSurface.create(app.createConfigurationContext(config)) as ViewGroup
            val density = root.resources.displayMetrics.density
            fun dp(n: Int) = (n * density + .5f).toInt()
            fun layout() { root.measure(exact(dp(288)), exact(dp(466))); root.layout(0, 0, root.measuredWidth, root.measuredHeight) }
            for (active in listOf(false, true)) {
                PayCardSurface.render(root, selection().apply { if (active) putString("selected", "wx_pay") })
                layout()
                val gear = root.findViewWithTag<View>(if (active) "pay_fullscreen" else "pay_settings")
                assertTrue(gear.width >= dp(48) && gear.height >= dp(48))
                val bounds = Rect(0, 0, gear.width, gear.height); root.offsetDescendantRectToMyCoords(gear, bounds)
                assertTrue(Rect(0, 0, root.width, root.height).contains(bounds))
                if (active) {
                    val viewport = root.findViewWithTag<View>("pay_viewport")
                    val rect = Rect(0, 0, viewport.width, viewport.height); root.offsetDescendantRectToMyCoords(viewport, rect)
                    assertEquals(Rect(dp(21), dp(51), dp(288) - dp(21), dp(466) - dp(85)), rect)
                }
                for (type in selection().getStringArray("types")!!) {
                    val label = descendants(root.findViewWithTag("${if (active) "pay_tab" else "pay_function"}_$type")).filterIsInstance<TextView>().single()
                    assertTrue("PAY $type active=$active scale=$scale layout=${label.layout.height} height=${label.height}", label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                }
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(image))
                    val file = File(directory, "native-pay-${if (active) "open" else "idle"}-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$scale-${android.os.Build.VERSION.SDK_INT}.png")
                    file.parentFile?.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
            }
        }
    }
    private fun exact(n: Int) = View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY)
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
