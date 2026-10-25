package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class NativeCardLayoutTest {
    @Test fun targetPhoneMatchesMeasuredOriginalBounds() {
        val card = NativeCardLayout.measure(1440, 3168, 4f)
        assertEquals(NativeCardLayout(1152, 1864, 144, 144), card)
        assertEquals(1160, 3168 - card.bottom - card.height)
    }
    @Test fun originalMinimumBordersAndBottomAnchorSurviveDifferentDisplays() {
        for (density in listOf(.75f, 1f, 2f, 4f)) for (width in listOf(320, 360, 480, 800)) {
            val card = NativeCardLayout.measure((width * density).toInt(), (792 * density).toInt(), density)
            assertEquals(minOf(288, width - 72) * density, card.width.toFloat(), 1f)
            assertEquals(36 * density, card.bottom.toFloat(), 1f)
            assertTrue(card.left >= 36 * density - 1)
        }
        val compact = NativeCardLayout.measure(600, 400, 2f)
        assertTrue(compact.height > 0 && compact.height + compact.bottom <= 400)
    }
}
