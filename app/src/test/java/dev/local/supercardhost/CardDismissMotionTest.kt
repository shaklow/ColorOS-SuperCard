package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardDismissMotionTest {
    @Test fun secondCardReturnsToAStackBeforeLeavingInsteadOfBringingBackAFlatRow() {
        val motion = CardDismissMotion(0f, 1336f, 1152f, 4f)
        assertEquals(135L, motion.rollbackMillis)
        assertEquals(662L, motion.durationMillis)
        assertEquals(1152f, motion.scroll(0f), 0f)
        assertTrue(motion.scroll(.05f) in 400f..500f)
        assertEquals(0f, motion.scroll(.135f), 0f)
        val first = CardMotionRules.stackFrame(-motion.scroll(.15f) / 1152, 1152f, 4f)
        val second = CardMotionRules.stackFrame(1 - motion.scroll(.15f) / 1152, 1152f, 4f)
        assertEquals(0f, first.x, 0f)
        assertEquals(.95f, second.scale, .001f)
        assertEquals(13.6f * 4 + 1152 * .025f, second.x, .001f)
        assertTrue(motion.translation(.15f) in 600f..1000f)
        assertEquals(1336f, motion.translation(.662f), 0f)
    }
    @Test fun firstCardUsesTheOriginalShorterDismissalAndPreservesStartingPosition() {
        val motion = CardDismissMotion(140f, 1336f, 0f, 4f)
        assertEquals(458L, motion.durationMillis)
        assertEquals(140f, motion.translation(0f), .001f)
        assertEquals(0f, motion.scroll(.05f), 0f)
        assertTrue(motion.translation(.1f) > 800f)
        assertEquals(1336f, motion.translation(.458f), 0f)
    }
}
