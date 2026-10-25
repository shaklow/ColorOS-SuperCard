package dev.local.supercardhost

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayCodeDrawableTest {
    @Test fun moduleDrawingsDecodeAtCardSizesAndNeverPaintOutsideTheirBounds() {
        val value = "100000000000000001" // Synthetic fixture, never a captured payment credential.
        for (density in listOf(1, 2, 3)) for (qr in listOf(true, false)) for (compact in listOf(false, true)) {
            val width = (if (compact) 210 else 230) * density
            val height = (if (qr) if (compact) 165 else 210 else 38) * density
            val bitmap = Bitmap.createBitmap(width + 20, height + 20, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.MAGENTA)
                val drawable = if (qr) PayCodeDrawable.qr(value) else PayCodeDrawable.barcode(value)
                drawable.setBounds(10, 10, width + 10, height + 10); drawable.draw(canvas)
                assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0))
                val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 10, 10, width, height)
                assertTrue(pixels.all { it == Color.WHITE || it == Color.BLACK })
                val format = if (qr) BarcodeFormat.QR_CODE else BarcodeFormat.CODE_128
                val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(format)))
                assertEquals(format, result.barcodeFormat); assertEquals(value, result.text)
                assertEquals(Color.WHITE, bitmap.getPixel(10, 10))
            } finally { bitmap.recycle() }
        }
    }
    @Test fun invalidValuesCannotBecomeAVisibleCode() {
        for (value in listOf("", "garbage", "https://example.test/", "199999999999999999")) {
            try { PayCodeDrawable.qr(value); fail("Invalid code accepted") } catch (_: IllegalArgumentException) { }
            try { PayCodeDrawable.barcode(value); fail("Invalid code accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
