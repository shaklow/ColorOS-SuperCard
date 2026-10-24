package dev.local.supercardhost

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Keeps wallet-provided QR images/payloads distinct from verification text and external links. */
internal object NativeCouponCodes {
    fun decode(row: NativeCouponData.Item): Bitmap? {
        if (!row.usable || row.douyin) return null
        if (row.image.isNotEmpty()) {
            require(row.image.length <= 700_000)
            val bytes = Base64.decode(if (row.image.startsWith("data:")) row.image.substringAfter(',') else row.image, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
            val options = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 1 }
            while (bounds.outWidth / options.inSampleSize > 1024 || bounds.outHeight / options.inSampleSize > 1024) options.inSampleSize *= 2
            return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        }
        if (row.qr.isEmpty()) return null
        require(row.qr.length <= 4096)
        val matrix = MultiFormatWriter().encode(row.qr, BarcodeFormat.QR_CODE, 600, 600,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val pixels = IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) 0xff000000.toInt() else -1 }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
