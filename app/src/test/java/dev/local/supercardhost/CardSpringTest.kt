package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardSpringTest {
    @Test fun playbackMatchesOriginalSamplesWithoutCuttingOffTheTail() {
        for ((duration, expected) in listOf(.35f to 602.1951f, .175f to 528.4259f, .105f to 485.7420f)) {
            val spring = CardSpring(900f, 0f, -2400f, duration)
            assertEquals(expected, spring.position(.1f), .02f)
        }
        val spring = CardSpring(900f, 0f, -2400f, .35f)
        assertEquals(15.0904f, spring.position(.55f), .01f)
        assertEquals(0f, spring.position(.856f), 0f)
    }
    @Test fun releaseKeepsPositionAndUsesTheOriginalPlaybackClockThenSettles() {
        for (velocity in listOf(-8000f, -500f, 0f, 1000f)) {
            val spring = CardSpring(420f, 0f, velocity, .35f)
            assertEquals(420f, spring.position(0f), .001f)
            assertEquals(velocity * spring.clockRate, spring.velocity(0f), .01f)
            assertEquals(856L, spring.durationMillis)
            assertEquals(0f, spring.position(.86f), 0f)
            assertEquals(0f, spring.velocity(.86f), 0f)
        }
    }
    @Test fun rearCardCanFollowReleaseWithItsOwnMomentum() {
        val front = CardSpring(0f, 0f, -1400f, .35f)
        val rear = CardSpring(40f, 0f, -1260f, .35f)
        assertTrue(front.position(.03f) < 0f)
        assertNotEquals(front.position(.03f), rear.position(.03f))
        assertEquals(0f, rear.position(.86f), 0f)
    }
}
