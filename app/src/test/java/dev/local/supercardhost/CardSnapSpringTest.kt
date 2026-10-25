package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardSnapSpringTest {
    @Test fun matchesOriginalQuickSwitchTension145AndFriction20Landmarks() {
        val spring = CardSnapSpring(100f, 0f, 0f)
        for ((time, expected) in listOf(.05f to 87.0343f, .1f to 62.9067f,
            .2f to 22.7214f, .4f to -.43456f, .6f to -.44288f)) {
            assertEquals(expected, spring.position(time), .001f)
        }
        assertEquals(0f, spring.position(spring.duration / 1000f), 1f)
        assertTrue(kotlin.math.abs(spring.velocity(spring.duration / 1000f)) < 1f)
    }
    @Test fun interruptRestartsFromTheActualFrameAndReleaseVelocity() {
        val first = CardSnapSpring(0f, 1152f, 2400f)
        val held = first.position(.12f)
        val reverse = CardSnapSpring(held, 0f, -1600f)
        assertEquals(held, reverse.position(0f), .001f)
        assertEquals(-1600f, reverse.velocity(0f), .01f)
    }
    @Test fun catchingAReboundOutsideTheLastSlotDoesNotSnapBackToTheBoundary() {
        val flight = CardSnapSpring(800f, 1152f, 10000f)
        val held = flight.position(.1f)
        assertTrue(held > 1152f)
        assertEquals(held - 8f, CardMotionRules.dragScroll(held, 8f, 1152f), .001f)
        assertEquals(-42f, CardMotionRules.dragScroll(-50f, -8f, 1152f), .001f)
    }
    @Test fun originalStackKeepsOutgoingLeftEdgeAndInterpolatesDepth() {
        val old = CardMotionRules.stackFrame(-1f, 288f, 1f)
        assertEquals(-308f, old.x, .001f)
        assertEquals(1f, old.scale, 0f)
        val rear = CardMotionRules.stackFrame(1f, 288f, 1f)
        assertEquals(20.8f, rear.x, .001f)
        assertEquals(.95f, rear.scale, .001f)
        assertEquals(-8f, rear.z, 0f)
        assertEquals(.12f, rear.shade, .001f)
        assertEquals(.16f, CardMotionRules.stackFrame(1.5f, 288f, 1f).shade, .001f)
    }
}
