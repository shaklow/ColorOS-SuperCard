package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardMotionRulesTest {
    @Test fun edgeMatchesOriginalDistanceVelocityAndReverseFlingRulesAtEveryDensity() {
        for (density in listOf(.75f, 1f, 2f, 3.5f)) {
            fun edge(distance: Float, velocity: Float, locked: Boolean = false) =
                CardMotionRules.edgeCommit(distance * density, velocity * density, locked, density)
            assertFalse(edge(120f, 0f)); assertTrue(edge(121f, 0f))
            assertFalse(edge(150f, 0f, true)); assertTrue(edge(151f, 0f, true))
            assertTrue(edge(20f, -301f)); assertFalse(edge(200f, 301f))
            assertFalse(edge(30f, -300f)); assertFalse(edge(0f, 0f))
        }
        assertFalse(CardMotionRules.edgeCommit(Float.NaN, -1000f, false, 1f))
        assertFalse(CardMotionRules.edgeCommit(200f, 0f, false, 0f))
    }
    @Test fun pageSupportsSlowDragFlingReversalCancellationAndBounds() {
        fun page(x: Float, velocity: Float) = CardMotionRules.pageDelta(x, velocity, 280f, 1f)
        assertEquals(0, page(-60f, 0f)); assertEquals(1, page(-71f, 0f))
        assertEquals(-1, page(71f, 0f)); assertEquals(1, page(-20f, -501f))
        assertEquals(0, page(-200f, 501f)); assertEquals(0, page(200f, -501f))
        assertEquals(0, page(0f, 2000f)); assertEquals(0, page(Float.NaN, 0f))
        assertEquals(0, CardMotionRules.pageDelta(-100f, 0f, 0f, 1f))
        assertEquals(0, CardMotionRules.pageDelta(-100f, 0f, 280f, Float.NaN))
    }
    @Test fun settleTimeIsBoundedAndUsesRemainingTravelAndReleaseSpeed() {
        assertTrue(CardMotionRules.duration(30f, 0f, 280f) < CardMotionRules.duration(280f, 0f, 280f))
        assertTrue(CardMotionRules.duration(280f, 3000f, 280f) < CardMotionRules.duration(280f, 0f, 280f))
        for (distance in listOf(-1000f, 0f, 280f, 1000f))
            assertTrue(CardMotionRules.duration(distance, 0f, 280f) in 140L..320L)
        assertEquals(240L, CardMotionRules.duration(Float.NaN, 0f, 280f))
    }
}
