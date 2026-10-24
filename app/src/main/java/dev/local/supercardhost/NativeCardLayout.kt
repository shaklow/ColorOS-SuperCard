package dev.local.supercardhost

/** Bundled OEM CardViewHelper/CarouselAdapter dimensions, independent of system bar insets. */
internal data class NativeCardLayout(val width: Int, val height: Int, val left: Int, val bottom: Int) {
    companion object {
        fun measure(screenWidth: Int, screenHeight: Int, density: Float): NativeCardLayout {
            require(screenWidth > 0 && screenHeight > 0 && density.isFinite() && density > 0)
            fun dp(value: Int) = (value * density + .5f).toInt()
            val width = minOf(dp(288), screenWidth - dp(72)).coerceAtLeast(1)
            val bottom = minOf(dp(36), screenHeight - 1)
            return NativeCardLayout(width, minOf(dp(466), screenHeight - bottom), (screenWidth - width) / 2, bottom)
        }
    }
}
