package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardFollowSpringTest {
    @Test fun firstConfirmedMoveDoesNotJumpToTheFingerOffset() {
        val follow = CardFollowSpring(1336f)
        follow.advance(1184f, 1f / 120)
        assertTrue(follow.position in 1325f..1336f)
        assertTrue(follow.velocity < 0f)
    }
    @Test fun retargetingKeepsMomentumAndDoesNotDependOnRefreshRate() {
        fun sample(hz: Int): CardFollowSpring {
            val follow = CardFollowSpring(1336f)
            repeat(hz / 5) { follow.advance(1000f, 1f / hz) }
            val position = follow.position; val velocity = follow.velocity
            follow.advance(500f, 0f)
            assertEquals(position, follow.position, 0f)
            assertEquals(velocity, follow.velocity, 0f)
            repeat(hz / 5) { follow.advance(500f, 1f / hz) }
            return follow
        }
        val low = sample(60); val high = sample(120)
        assertEquals(low.position, high.position, .01f)
        assertEquals(low.velocity, high.velocity, .1f)
        assertTrue(high.position in 500f..505f)
    }
}
