package dev.local.supercardui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** A real M3E button with a centered 24dp glyph, independent of empty text layout. */
class CardIconButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    MaterialButton(context, attrs, com.google.android.material.R.attr.materialIconButtonStyle) {
    private var glyph: Drawable? = super.getIcon()?.mutate()
        ?: AppCompatResources.getDrawable(context, R.drawable.dhu_ic_settings)?.mutate()
    private val size = resources.getDimensionPixelSize(R.dimen.card_settings_glyph)

    init {
        // MDC's compound drawable can be clipped by a constrained, empty TextView.
        // Keep its shape/ripple/semantics, and draw the glyph in the full touch target.
        super.setIcon(null)
        setPadding(0, 0, 0, 0)
        minimumWidth = resources.getDimensionPixelSize(R.dimen.card_settings_target)
        minimumHeight = minimumWidth
    }

    fun setGlyph(drawable: Drawable?) { glyph = drawable?.mutate(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        glyph?.let {
            val left = (width - size) / 2
            val top = (height - size) / 2
            it.setBounds(left, top, left + size, top + size)
            it.setTint(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, "Card icon"))
            it.alpha = if (isEnabled) 255 else 98
            it.draw(canvas)
        }
    }
}
