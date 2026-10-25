package dev.local.supercardhost

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor
import kotlin.math.min

/** Module rectangles, not a cached pixel image. Grid edges always land on whole pixels. */
internal class PayCodeDrawable private constructor(private val matrix: BitMatrix, private val square: Boolean) : Drawable() {
    private val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
    private val background = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val shape = Path().apply {
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix[x, y]) { x++; continue }
                val start = x++
                while (x < matrix.width && matrix[x, y]) x++
                addRect(start.toFloat(), y.toFloat(), x.toFloat(), (y + 1).toFloat(), Path.Direction.CW)
            }
        }
    }
    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        canvas.drawRect(area, background)
        val horizontal = floor(area.width().toFloat() / matrix.width)
        val vertical = if (square) floor(min(area.width().toFloat() / matrix.width, area.height().toFloat() / matrix.height)) else area.height().toFloat() / matrix.height
        val xScale = if (square) vertical else horizontal
        if (xScale < 1 || vertical < 1) return
        val save = canvas.save()
        canvas.translate(area.left + floor((area.width() - matrix.width * xScale) / 2), area.top + floor((area.height() - matrix.height * vertical) / 2))
        canvas.scale(xScale, vertical)
        canvas.drawPath(shape, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { /* Payment contrast is fixed. */ }
    @Deprecated("Framework Drawable contract") override fun getOpacity() = PixelFormat.OPAQUE
    companion object {
        fun qr(value: String): PayCodeDrawable = encode(value, BarcodeFormat.QR_CODE, true)
        fun barcode(value: String): PayCodeDrawable = encode(value, BarcodeFormat.CODE_128, false)
        private fun encode(value: String, format: BarcodeFormat, square: Boolean): PayCodeDrawable {
            require(WechatCodeWire.payment(value))
            val hints = mapOf(EncodeHintType.MARGIN to if (square) 4 else 20,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8")
            return PayCodeDrawable(MultiFormatWriter().encode(value, format, 0, if (square) 0 else 1, hints), square)
        }
    }
}
