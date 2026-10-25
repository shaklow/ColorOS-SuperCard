package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class GestureDownTest {
    private val original = GestureDown(6, 4098, 0, 0, 56906881L, 56906881L, 1435.625f, 2860.2559f)
    @Test fun capturedColorOsReplayRemainsTheSamePhysicalGesture() {
        // Actual device trace: the re-sent DOWN arrived after MOVE, with downTime rounded by 1ms.
        assertTrue(original.copy(downTime = 56906880L).isReplayOf(original))
        assertTrue(original.isReplayOf(original))
    }
    @Test fun aNewTouchOrAnotherDeviceMustStillCancelTheOldGesture() {
        assertFalse(original.copy(downTime = 56907000L, eventTime = 56907000L).isReplayOf(original))
        assertFalse(original.copy(device = 7).isReplayOf(original))
        assertFalse(original.copy(display = 1).isReplayOf(original))
        assertFalse(original.copy(source = 8194).isReplayOf(original))
        assertFalse(original.copy(pointer = 1).isReplayOf(original))
        assertFalse(original.copy(x = 1300f).isReplayOf(original))
    }
}
