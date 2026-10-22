package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import java.lang.ref.WeakReference
import kotlin.math.min

/** Painted into the OEM idle frame: inherits its alpha/visibility and owns no input or lifecycle. */
internal class PayIdleArtwork(context: Context, actions: ViewGroup) : Drawable() {
    private val d = CardDesign(context)
    private val actions = WeakReference(actions)
    private val tonal = MaterialShapeDrawable(
        ShapeAppearanceModel.builder().setAllCornerSizes(d.dp(40).toFloat()).build()
    ).apply { fillColor = ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorPrimaryContainer)) }
    private val glyph = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.pay_ic_wallet))
        .mutate().apply { setTint(d.color(com.google.android.material.R.attr.colorOnPrimaryContainer)) }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidateSelf() }

    // Sibling layout changes must invalidate the frame's cached hardware display list too.
    fun attach() { actions.get()?.addOnLayoutChangeListener(layoutListener) }
    fun dispose() { actions.get()?.removeOnLayoutChangeListener(layoutListener) }

    override fun draw(canvas: Canvas) {
        // Reserve the real OEM shortcut/bill area, including changes to the enabled functions.
        val bottom = actions.get()?.top ?: return
        val top = bounds.top + d.dp(76)
        val available = min(bottom, bounds.bottom) - d.dp(16) - top
        if (available < d.dp(64)) return
        val size = min(d.dp(104), min(available, bounds.width() - d.dp(64)))
        if (size <= 0) return
        val left = bounds.centerX() - size / 2
        val y = top + (available - size) / 2
        tonal.setBounds(left, y, left + size, y + size)
        val inset = size / 4
        glyph.setBounds(left + inset, y + inset, left + size - inset, y + size - inset)
        tonal.draw(canvas)
        glyph.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { tonal.alpha = alpha; glyph.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { tonal.colorFilter = filter; glyph.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
