package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.ListView
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
class CouponCardSurfaceTest {
    private fun rows() = arrayListOf(Bundle().apply {
        putString("key", "film___one"); putString("title", "电影票 · 测试影厅")
        putString("category", "电影票"); putString("status", "待使用"); putBoolean("usable", true)
        putString("merchant", "测试影院"); putString("time", "2026年10月24日 20:00 - 22:00")
        putBoolean("code", true); putString("hint", "二维码来自钱包，请向商家出示")
    }, Bundle().apply {
        putString("key", "coupon___two"); putString("title", "团购券 · 双人套餐")
        putString("category", "消费券"); putString("status", "待使用"); putBoolean("usable", true)
        putString("hint", "抖音未提供券码，需在抖音内使用")
    })
    private fun state(selected: String? = null) = Bundle().apply { putParcelableArrayList("items", rows()); putString("selected", selected) }
    @Test fun actionsPreserveIdentifiersAndRemovalAlwaysRequiresConfirmation() {
        val root = CouponCardSurface.create(RuntimeEnvironment.getApplication())
        val actions = mutableListOf<Bundle>()
        CouponCardSurface.bind(root, Consumer { actions.add(it) })
        CouponCardSurface.render(root, state())
        root.measure(exact(288), exact(466)); root.layout(0, 0, 288, 466)
        root.findViewWithTag<View>("coupon_item_film___one").performClick()
        assertEquals("select", actions.last().getString("action")); assertEquals("film___one", actions.last().getString("key"))
        CouponCardSurface.render(root, state("film___one"))
        val image = root.findViewWithTag<ImageView>("coupon_qr")
        root.findViewWithTag<View>("coupon_remove").performClick()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("coupon_confirm").visibility)
        assertEquals(1, actions.size)
        root.findViewWithTag<View>("coupon_cancel_remove").performClick()
        assertEquals(1, actions.size)
        root.findViewWithTag<View>("coupon_remove").performClick()
        root.findViewWithTag<View>("coupon_confirm_remove").performClick()
        assertEquals("remove", actions.last().getString("action")); assertEquals("film___one", actions.last().getString("key"))
        CouponCardSurface.render(root, state("film___one").apply { putString("notice", "正在补全券码") })
        assertSame(image, root.findViewWithTag("coupon_qr"))
        CouponCardSurface.render(root, Bundle().apply { putString("title", "钱包账号已切换") })
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("coupon_empty").visibility)
        assertEquals(View.INVISIBLE, root.findViewWithTag<View>("coupon_detail").visibility)
        assertNull(root.findViewWithTag<View>("coupon_item_film___one"))
    }
    @Test fun manyTicketsReuseVisibleRowsAndRebindTheirClickIdentifiers() {
        val root = CouponCardSurface.create(RuntimeEnvironment.getApplication())
        val actions = mutableListOf<Bundle>()
        CouponCardSurface.bind(root, Consumer { actions.add(it) })
        CouponCardSurface.render(root, Bundle().apply {
            putParcelableArrayList("items", ArrayList((0 until 100).map { index -> Bundle().apply {
                putString("key", "coupon___$index"); putString("title", "券 $index"); putString("category", "消费券"); putString("status", "待使用")
            } }))
        })
        fun layout() { root.measure(exact(288), exact(466)); root.layout(0, 0, 288, 466) }
        layout()
        val list = root.findViewWithTag<ListView>("coupon_list")
        assertEquals(101, list.adapter.count) // one non-selectable status header
        assertTrue("Only visible rows should be created", list.childCount in 1..10)
        list.setSelection(51); layout()
        root.findViewWithTag<View>("coupon_item_coupon___50").performClick()
        assertEquals("coupon___50", actions.last().getString("key"))
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun listDetailAndUnavailableStatesFitOriginalBoundsBothThemesAndLargeText() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) for (font in listOf(1f, 1.3f, 1.6f)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply { fontScale = font; uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night }
            val root = CouponCardSurface.create(app.createConfigurationContext(config)) as ViewGroup
            val density = root.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + .5f).toInt()
            val phases = listOf("list" to state(), "detail" to state("film___one"), "douyin" to state("coupon___two"),
                "unavailable" to Bundle().apply { putString("title", "钱包尚未连接"); putString("description", "请先打开钱包，再重新展开卡包") })
            for ((phase, data) in phases) {
                CouponCardSurface.render(root, data)
                root.measure(exact(dp(288)), exact(dp(466))); root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val settings = root.findViewWithTag<View>("coupon_settings")
                assertTrue(settings.width >= dp(48) && settings.height >= dp(48))
                assertTrue(settings.right <= root.width)
                val scroll = root.findViewWithTag<View>(if (phase == "unavailable") "coupon_empty" else if (phase == "list") "coupon_list" else "coupon_detail")
                assertTrue(scroll.width > 0 && scroll.height > 0)
                val bounds = Rect(0, 0, scroll.width, scroll.height); root.offsetDescendantRectToMyCoords(scroll, bounds)
                assertEquals(dp(16), bounds.left)
                assertEquals(dp(58), bounds.top)
                if (phase == "douyin") assertEquals(View.GONE, root.findViewWithTag<View>("coupon_qr").parent.let { it as View }.visibility)
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val file = File(directory, "native-coupon-$phase-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$font-${android.os.Build.VERSION.SDK_INT}.png")
                    file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
        }
    }
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
}
