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
import com.google.android.material.textview.MaterialTextView

/** Shared semantic roles for the Kotlin card container and its feature surfaces. */
internal class CardDesign(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun color(attr: Int) = MaterialColors.getColor(context, attr, "SuperCard cards")
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Int, secondary: Boolean = false, bold: Boolean = false) = MaterialTextView(context).apply {
        text = value; textSize = size.toFloat(); setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(color(if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun icon(action: String, description: String, drawable: Int) = MaterialButton(context, null,
        com.google.android.material.R.attr.materialIconButtonStyle).apply {
        tag = "native_$action"; contentDescription = description
        icon = AppCompatResources.getDrawable(context, drawable)
        iconTint = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }
    fun button(action: String, label: String) = MaterialButton(context, null,
        com.google.android.material.R.attr.materialButtonTonalStyle).apply {
        tag = "native_$action"; text = label; isAllCaps = false; minHeight = dp(52)
    }
    fun card(body: View, primary: Boolean = false) = MaterialCardView(context).apply {
        setCardBackgroundColor(color(if (primary) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow))
        radius = context.resources.getDimension(R.dimen.settings_card_corner)
        cardElevation = 0f; strokeWidth = 0
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }
}
