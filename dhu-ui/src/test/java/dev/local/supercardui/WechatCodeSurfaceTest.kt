package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import kotlin.math.floor
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WechatCodeSurfaceTest {
    @Test fun nativeContentFitsTheOriginalPaymentViewportAndExposesNoCredentialText() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) for (scale in listOf(1f, 1.3f, 1.6f)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply { fontScale = scale; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
            val root = WechatCodeSurface.create(app.createConfigurationContext(config)) as ViewGroup
            val density = root.resources.displayMetrics.density
            val width = (246 * density + .5f).toInt(); val height = (330 * density + .5f).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val qr = root.findViewWithTag<ImageView>("wechat_native_qr")
            val bar = root.findViewWithTag<ImageView>("wechat_native_barcode")
            assertTrue(qr.width > 0 && qr.height >= 150 * density)
            assertTrue(bar.width > 0 && bar.height >= 38 * density)
            assertTrue(qr.top >= bar.bottom && qr.bottom <= height)
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            assertEquals(listOf("微信支付", "向商家出示 · 点击进入微信"), descendants(root).filterIsInstance<TextView>().map { it.text.toString() })
            for (label in descendants(root).filterIsInstance<TextView>()) assertTrue(label.layout.height <= label.height)
            assertSame(qr.parent, bar.parent)
            qr.setImageDrawable(fixture(true)); bar.setImageDrawable(fixture(false))
            var clicks = 0; root.setOnClickListener { clicks++ }; root.performClick(); assertEquals(1, clicks)
            System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val file = File(directory, "native-wechat-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$scale-${android.os.Build.VERSION.SDK_INT}.png")
                    file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                val card = PayCardSurface.create(app.createConfigurationContext(config))
                PayCardSurface.render(card, Bundle().apply {
                    putStringArray("types", arrayOf("wx_scan", "alipay_scan", "wx_pay", "alipay_pay"))
                    putStringArray("titles", arrayOf("微信扫一扫", "支付宝扫一扫", "微信付款码", "支付宝付款码"))
                    putString("selected", "wx_pay")
                })
                card.findViewWithTag<FrameLayout>("pay_viewport").addView(root, FrameLayout.LayoutParams(-1, -1))
                val cardWidth = (288 * density + .5f).toInt(); val cardHeight = (466 * density + .5f).toInt()
                card.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cardHeight, View.MeasureSpec.EXACTLY))
                card.layout(0, 0, cardWidth, cardHeight)
                val full = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
                try {
                    card.draw(Canvas(full))
                    File(directory, "native-wechat-card-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$scale-${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { full.recycle() }
            }
        }
    }
    private fun fixture(qr: Boolean): Drawable {
        val matrix = MultiFormatWriter().encode("100000000000000001", if (qr) BarcodeFormat.QR_CODE else BarcodeFormat.CODE_128,
            0, if (qr) 0 else 1, mapOf(EncodeHintType.MARGIN to if (qr) 4 else 20))
        return object : Drawable() {
            val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
            override fun draw(canvas: Canvas) {
                val scale = floor(if (qr) min(bounds.width().toFloat() / matrix.width, bounds.height().toFloat() / matrix.height) else bounds.width().toFloat() / matrix.width)
                val vertical = if (qr) scale else bounds.height().toFloat()
                val saved = canvas.save()
                canvas.translate(bounds.left + (bounds.width() - matrix.width * scale) / 2, bounds.top + (bounds.height() - matrix.height * vertical) / 2)
                canvas.scale(scale, vertical)
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
                canvas.restoreToCount(saved)
            }
            override fun setAlpha(alpha: Int) { }
            override fun setColorFilter(filter: ColorFilter?) { }
            @Deprecated("Drawable contract") override fun getOpacity() = PixelFormat.OPAQUE
        }
    }
}
