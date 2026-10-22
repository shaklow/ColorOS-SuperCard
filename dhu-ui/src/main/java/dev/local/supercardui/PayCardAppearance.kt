package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/** Framework views cross the loader boundary; MDC types and resource IDs stay in this archive. */
object PayCardAppearance {
    @JvmStatic fun style(resources: Context, part: String, views: Array<View>) {
        require(views.size == if (part == "card") 6 else 4)
        val context = ContextThemeWrapper(resources, R.style.Theme_DhuUi)
        val d = CardDesign(context)
        when (part) {
            "card" -> card(context, d, views)
            "function" -> function(d, views)
            else -> throw IllegalArgumentException("Unknown PAY appearance part")
        }
    }

    private fun shape(d: CardDesign, colors: ColorStateList, corner: Int) = MaterialShapeDrawable(
        ShapeAppearanceModel.builder().setAllCornerSizes(d.dp(corner).toFloat()).build()
    ).apply { fillColor = colors }

    private fun card(context: Context, d: CardDesign, views: Array<View>) {
        val frame = views[0] as ImageView
        val logo = views[1] as ImageView
        val settings = views[2] as ImageView
        val hint = views[3] as TextView
        val shadow = views[4]
        val actions = views[5] as ViewGroup
        // Prepare dependencies before changing any OEM view.
        val surface = ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSurfaceContainerLow))
        val artwork = PayIdleArtwork(context, actions)
        val frameBackground = LayerDrawable(arrayOf(shape(d, surface, 28), artwork))
        val shadowBackground = shape(d, surface, 28)
        val primary = d.color(androidx.appcompat.R.attr.colorPrimary)
        val foreground = d.color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val icon = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.dhu_ic_settings)).mutate().apply { setTint(foreground) }
        val buttonBackground = shape(d, ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSecondaryContainer)), 24)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorControlHighlight)),
            buttonBackground, shape(d, ColorStateList.valueOf(Color.WHITE), 24)
        )
        (frame.background as? LayerDrawable)?.let { previous ->
            for (index in 0 until previous.numberOfLayers) (previous.getDrawable(index) as? PayIdleArtwork)?.dispose()
        }
        frame.background = frameBackground
        artwork.attach()
        // OEM bg_shadow is a 20dp translucent white fill, not just an external shadow.
        // Match both silhouettes so it cannot show through the 28dp foreground corners.
        // Keep its original elevation, fade visibility, animation and layout ownership.
        shadow.background = shadowBackground
        logo.imageTintList = ColorStateList.valueOf(primary)
        settings.setImageDrawable(icon)
        settings.clearColorFilter()
        settings.imageTintList = ColorStateList.valueOf(foreground)
        settings.background = ripple
        settings.setPadding(d.dp(12), d.dp(12), d.dp(12), d.dp(12))
        settings.minimumWidth = d.dp(48); settings.minimumHeight = d.dp(48)
        settings.layoutParams?.let { params ->
            params.width = d.dp(48); params.height = d.dp(48)
            if (params is ViewGroup.MarginLayoutParams) {
                params.topMargin = d.dp(8); params.marginEnd = d.dp(8)
            }
            settings.layoutParams = params
        }
        settings.contentDescription = "PAY 设置"
        hint.setTextColor(foreground)
        views.forEach { it.isForceDarkAllowed = false }
    }

    private fun function(d: CardDesign, views: Array<View>) {
        val root = views[0]
        val selected = views[1] as ImageView
        val icon = views[2] as ImageView
        val label = views[3] as TextView
        val surface = d.color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val active = d.color(com.google.android.material.R.attr.colorPrimaryContainer)
        val foreground = d.color(com.google.android.material.R.attr.colorOnSurface)
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(active, surface))
        val overlay = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(active, Color.TRANSPARENT))
        val background = shape(d, colors, 20)
        val selection = shape(d, overlay, 20)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorControlHighlight)),
            null, shape(d, ColorStateList.valueOf(Color.WHITE), 20)
        )
        root.background = background
        root.foreground = ripple
        root.elevation = 0f
        selected.background = selection
        label.setTextColor(foreground)
        icon.clearColorFilter()
        icon.imageTintList = ColorStateList.valueOf(foreground)
        // OEM sizes, state, text, drawables, click/touch listeners and child ownership remain intact.
        views.forEach { it.isForceDarkAllowed = false }
    }
}
