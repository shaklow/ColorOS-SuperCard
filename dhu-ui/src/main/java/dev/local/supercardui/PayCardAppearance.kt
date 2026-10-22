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
        val frameBackground = LayerDrawable(arrayOf(d.shape(surface, R.dimen.card_surface_corner), artwork))
        val shadowBackground = d.shape(surface, R.dimen.card_surface_corner)
        val primary = d.color(androidx.appcompat.R.attr.colorPrimary)
        val foreground = d.color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val icon = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.dhu_ic_settings)).mutate().apply { setTint(foreground) }
        val buttonBackground = d.shape(ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSecondaryContainer)), R.dimen.card_settings_corner)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorControlHighlight)),
            buttonBackground, d.shape(ColorStateList.valueOf(Color.WHITE), R.dimen.card_settings_corner)
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
        val target = d.size(R.dimen.card_settings_target)
        val inset = (target - d.size(R.dimen.card_settings_glyph)) / 2
        settings.setPadding(inset, inset, inset, inset)
        settings.minimumWidth = target; settings.minimumHeight = target
        settings.layoutParams?.let { params ->
            params.width = target; params.height = target
            if (params is ViewGroup.MarginLayoutParams) {
                params.topMargin = d.size(R.dimen.card_action_gap); params.marginEnd = d.size(R.dimen.card_action_gap)
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
        val background = d.shape(colors, R.dimen.card_inner_corner)
        val selection = d.shape(overlay, R.dimen.card_inner_corner)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorControlHighlight)),
            null, d.shape(ColorStateList.valueOf(Color.WHITE), R.dimen.card_inner_corner)
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
