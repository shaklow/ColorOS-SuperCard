package dev.local.supercardhost

/** Immutable gesture decisions. No Context, Android geometry, reflection or window ownership. */
internal object GesturePolicy {
    data class Settings(
        val total: Boolean, val side: String?, val range: String?,
        val lockSwitch: Boolean, val lockStyle: Int, val restSwitch: Boolean, val restStyle: Int
    )
    data class Screen(val locked: Boolean, val interactive: Boolean)
    data class Display(val width: Int, val height: Int, val density: Float)
    data class Bounds(@JvmField val left: Int, @JvmField val top: Int, @JvmField val right: Int, @JvmField val bottom: Int) {
        fun empty() = left >= right || top >= bottom
        fun contains(x: Float, y: Float) = x.isFinite() && y.isFinite() && !empty() &&
            x.toInt() >= left && x.toInt() < right && y.toInt() >= top && y.toInt() < bottom
    }
    @JvmField val EMPTY = Bounds(0, 0, 0, 0)
    data class Decision(
        @JvmField val settings: Settings, @JvmField val screen: Screen, @JvmField val display: Display,
        @JvmField val edge: Int, @JvmField val branch: Int,
        @JvmField val navigationEnabled: Boolean, @JvmField val lockEnabled: Boolean, @JvmField val restEnabled: Boolean,
        @JvmField val navigationRegion: Bounds, @JvmField val region: Bounds
    ) {
        fun hintRegion(visible: Boolean, cardShown: Boolean) =
            if (visible && screen.interactive && !cardShown) region else EMPTY
    }

    @JvmStatic fun evaluate(settings: Settings, screen: Screen, display: Display): Decision {
        require(display.width > 0 && display.height > 0 && display.density.isFinite() && display.density > 0)
        val edge = GestureSettingsRules.edge(display.width, display.density)
        val side = settings.side?.takeUnless { it.isEmpty() } ?: GestureSettingsRules.DEFAULT_SIDE
        val normalized = settings.copy(side = side)
        val pixels = GestureSettingsRules.project(settings.range, display.width, display.height)
        val navigation = pixels?.let { Bounds(display.width - edge, it.top, display.width, it.bottom) } ?: EMPTY
        val navigationEnabled = GestureSettingsRules.sideEnabled(side) && !navigation.empty()
        val branch = if (!screen.interactive) 2 else if (screen.locked) 1 else 0
        fun styleRegion(style: Int) = when (style) {
            1 -> Bounds(display.width - edge, 0, display.width, display.height)
            2 -> if (navigationEnabled) navigation else EMPTY
            3 -> Bounds(display.width - edge, display.height * 2 / 3, display.width, display.height)
            else -> EMPTY
        }
        val lock = if (settings.total && branch == 1 && settings.lockSwitch) styleRegion(settings.lockStyle) else EMPTY
        val rest = if (settings.total && branch == 2 && settings.restSwitch) styleRegion(settings.restStyle) else EMPTY
        val region = when {
            !settings.total -> EMPTY
            branch == 0 -> if (navigationEnabled) navigation else EMPTY
            branch == 1 -> lock
            else -> rest
        }
        return Decision(normalized, screen, display, edge, branch, navigationEnabled, !lock.empty(), !rest.empty(), navigation, region)
    }
}
