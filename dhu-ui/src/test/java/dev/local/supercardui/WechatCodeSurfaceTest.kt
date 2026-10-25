package dev.local.supercardui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
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
            assertTrue(bar.width > 0 && bar.height >= 42 * density)
            assertTrue(qr.top >= bar.bottom && qr.bottom <= height)
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            assertEquals(listOf("微信付款码", "请向商家出示"), descendants(root).filterIsInstance<TextView>().map { it.text.toString() })
            for (label in descendants(root).filterIsInstance<TextView>()) assertTrue(label.layout.height <= label.height)
            qr.setImageDrawable(ColorDrawable(Color.WHITE)); bar.setImageDrawable(ColorDrawable(Color.WHITE))
            var clicks = 0; root.setOnClickListener { clicks++ }; root.performClick(); assertEquals(1, clicks)
            System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val file = File(directory, "native-wechat-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-$scale-${android.os.Build.VERSION.SDK_INT}.png")
                    file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
    }
}
