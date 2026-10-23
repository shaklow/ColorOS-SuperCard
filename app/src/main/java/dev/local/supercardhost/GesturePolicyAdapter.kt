package dev.local.supercardhost

import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.util.DisplayMetrics

/** The Secure-settings and Android-geometry boundary for both SystemUI consumers. */
internal object GesturePolicyAdapter {
    @JvmStatic fun read(context: Context, metrics: DisplayMetrics, locked: Boolean, interactive: Boolean): GesturePolicy.Decision {
        val resolver = context.contentResolver
        fun enabled(key: String, fallback: Int = 0) = Settings.Secure.getInt(resolver, key, fallback) == 1
        return GesturePolicy.evaluate(GesturePolicy.Settings(
            enabled("card_service_total_switch", 1),
            Settings.Secure.getString(resolver, "navigation_gesture_right_side"),
            Settings.Secure.getString(resolver, GestureSettingsRules.RANGE_KEY),
            enabled("bbk_lock_disable_card_slide_setting"),
            Settings.Secure.getInt(resolver, "bbk_lock_disable_card_slide_open_style", 0),
            enabled("bbk_screen_disable_card_slide_setting"),
            Settings.Secure.getInt(resolver, "bbk_screen_disable_card_slide_open_style", 0)
        ), GesturePolicy.Screen(locked, interactive), GesturePolicy.Display(metrics.widthPixels, metrics.heightPixels, metrics.density))
    }
    @JvmStatic fun rect(value: GesturePolicy.Bounds) = Rect(value.left, value.top, value.right, value.bottom)
}
