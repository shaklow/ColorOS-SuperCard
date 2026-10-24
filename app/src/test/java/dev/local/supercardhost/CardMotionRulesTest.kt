package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardMotionRulesTest {
    @Test fun liveStackCoversThePanelInBothDirectionsAtEveryIntermediateFrame() {
        for (density in listOf(.75f, 1f, 2f, 3.5f)) for (direction in listOf(-1, 1)) {
            val width = 280 * density
            for (step in 0..1000) {
                val p = step / 1000f
                val frame = CardMotionRules.pageFrame(-direction * width * p, width, direction, density)
                fun bounds(pose: CardMotionRules.Pose) = (pose.x + width * (1 - pose.scale) / 2) to
                    (pose.x + width * (1 + pose.scale) / 2)
                val a = bounds(frame.outgoing); val b = bounds(frame.incoming)
                val left = if (a.first <= b.first) a else b; val right = if (a.first <= b.first) b else a
                assertTrue("No exposed leading edge at $direction/$p", left.first <= .01f)
                assertTrue("No gap between live pages at $direction/$p", left.second + .01f >= right.first)
                assertTrue("No exposed trailing edge at $direction/$p", maxOf(left.second, right.second) + .01f >= width)
            }
            assertEquals(0f, CardMotionRules.pageFrame(-direction * width, width, direction, density).incoming.x, .001f)
        }
    }
    @Test fun edgeMatchesOriginalDistanceVelocityAndReverseFlingRulesAtEveryDensity() {
        for (density in listOf(.75f, 1f, 2f, 3.5f)) {
            fun edge(distance: Float, velocity: Float, locked: Boolean = false) =
                CardMotionRules.edgeCommit(distance * density, velocity * density, locked, density)
            assertFalse(edge(120f, 0f)); assertTrue(edge(121f, 0f))
            assertFalse(edge(40f, 0f))
            assertFalse(edge(150f, 0f, true)); assertTrue(edge(151f, 0f, true))
            assertTrue(edge(20f, -301f)); assertFalse(edge(200f, 301f))
            assertFalse(edge(30f, -300f)); assertFalse(edge(0f, 0f))
        }
        assertFalse(CardMotionRules.edgeCommit(Float.NaN, -1000f, false, 1f))
        assertFalse(CardMotionRules.edgeCommit(200f, 0f, false, 0f))
    }
    @Test fun wholeOutgoingCardTravelsPastScreenEdgeAndRearCardHasOriginalPeek() {
        for (density in listOf(.75f, 1f, 2f, 4f)) {
            val width = 288 * density; val left = 36 * density
            val exit = width + left + 10 * density
            val start = CardMotionRules.pageFrame(0f, width, 1, density, exit)
            assertEquals(13.6f * density, start.incoming.x - width * (1 - start.incoming.scale) / 2, .001f)
            val end = CardMotionRules.pageFrame(-width, width, 1, density, exit)
            assertTrue("Old card clears the physical display", left + end.outgoing.x + width < 0f)
            assertEquals(1f, end.outgoing.scale, 0f)
            val reverse = CardMotionRules.pageFrame(0f, width, -1, density, exit)
            assertTrue("Previous card starts outside the display", left + reverse.incoming.x + width < 0f)
        }
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
