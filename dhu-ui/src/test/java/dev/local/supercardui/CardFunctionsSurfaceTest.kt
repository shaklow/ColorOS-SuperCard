package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import com.google.android.material.materialswitch.MaterialSwitch
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
class CardFunctionsSurfaceTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun payAndLensLayoutsFitBothThemesAndLargeText() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val app = RuntimeEnvironment.getApplication()
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                fontScale = 1.3f; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            for (type in listOf("pay", "camera")) for (widthDp in listOf(320, 600)) {
                val entries = if (type == "pay") listOf("wx_scan" to "微信扫一扫", "alipay_scan" to "支付宝扫一扫", "wx_pay" to "微信付款码",
                    "alipay_pay" to "支付宝付款码", "wx_ride" to "微信乘车码", "alipay_ride" to "支付宝乘车码", "wx2_pay" to "微信分身付款码")
                else listOf("take_photo" to "拍照", "portrait" to "人像", "video" to "录像", "night_view" to "夜景", "master" to "大师模式",
                    "mirror" to "镜子", "panoramic" to "全景", "high_piexl" to "高像素", "mini_distance" to "微距", "star_sky" to "星空", "document_correction" to "文档矫正")
                val root = CardManagementSurface.createFunctions(context, type)
                CardManagementSurface.render(root, Bundle().apply {
                    putBoolean("writable", true); putBoolean("authorized", true); putString("message", "更改会自动保存")
                    putInt("minimum", if (type == "pay") 1 else 4); putInt("maximum", 6)
                    putParcelableArrayList("cards", ArrayList(entries.mapIndexed { i, value -> Bundle().apply {
                        putString("type", value.first); putString("title", value.second); putBoolean("enabled", i < if (type == "pay") 4 else 6)
                    } }))
                })
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt(); val height = (740 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                for ((key, _) in entries) {
                    val toggle = root.findViewWithTag<MaterialSwitch>("function_toggle_$key")
                    assertTrue(toggle.width > 0 && toggle.height >= 48 * density)
                    val card = root.findViewWithTag<View>("function_card_$key")
                    assertTrue(card.width <= width && card.height >= toggle.height)
                    assertEquals(View.GONE, root.findViewWithTag<View>("function_settings_$key").visibility)
                }
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val suffix = "$type-$widthDp-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${android.os.Build.VERSION.SDK_INT}"
                    save(root, File(directory, "functions-$suffix.png"))
                    val scroll = root.findViewWithTag<ScrollView>("function_scroll")
                    scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0))
                    save(root, File(directory, "functions-bottom-$suffix.png"))
                }
            }
        }
    }
    private fun save(root: View, file: File) {
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image)); file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
