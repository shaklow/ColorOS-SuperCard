package dev.local.supercardhost

import android.content.Context

/** Separate presentation preferences; never rewrites the original engine's selected modes. */
internal object NativeLensPreferences {
    private val defaults = setOf("take_photo", "portrait", "video", "night_view", "master", "high_piexl")
    fun selected(context: Context): Set<String> = context.getSharedPreferences("native_cards", Context.MODE_PRIVATE)
        .getStringSet("lens", defaults)?.toSet() ?: defaults
    fun save(context: Context, types: Set<String>): Boolean {
        val known = ColorOsLensModes.query(context).map { it.type() }.toSet()
        require(known.containsAll(types))
        return context.getSharedPreferences("native_cards", Context.MODE_PRIVATE).edit().putStringSet("lens", types).commit()
    }
}
