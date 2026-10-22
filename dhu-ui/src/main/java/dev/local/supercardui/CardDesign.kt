package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textview.MaterialTextView

/** Shared semantic roles for the Kotlin card container and its feature surfaces. */
internal class CardDesign(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun size(resource: Int) = context.resources.getDimensionPixelSize(resource)
    fun color(attr: Int) = MaterialColors.getColor(context, attr, "SuperCard cards")
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Int, secondary: Boolean = false, bold: Boolean = false) = MaterialTextView(context).apply {
        text = value; textSize = size.toFloat(); setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(color(if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun icon(action: String, description: String, drawable: Int) = CardIconButton(context).apply {
        tag = "native_$action"; contentDescription = description
        setGlyph(AppCompatResources.getDrawable(context, drawable))
        layoutParams = LinearLayout.LayoutParams(size(R.dimen.card_settings_target), size(R.dimen.card_settings_target))
    }
    fun button(action: String, label: String) = MaterialButton(context, null,
        com.google.android.material.R.attr.materialButtonTonalStyle).apply {
        tag = "native_$action"; text = label; isAllCaps = false; minHeight = size(R.dimen.card_action_min_height)
    }
    fun card(body: View, primary: Boolean = false) = MaterialCardView(context).apply {
        setCardBackgroundColor(color(if (primary) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow))
        radius = context.resources.getDimension(R.dimen.card_surface_corner)
        cardElevation = 0f; strokeWidth = 0
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }
    fun shape(colors: ColorStateList, corner: Int) = MaterialShapeDrawable(
        ShapeAppearanceModel.builder().setAllCornerSizes(context.resources.getDimension(corner)).build()
    ).apply { fillColor = colors }
}
