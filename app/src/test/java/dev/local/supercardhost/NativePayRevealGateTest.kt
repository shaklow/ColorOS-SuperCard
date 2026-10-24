package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class NativePayRevealGateTest {
    @Test fun firstFrameDoesNotExposeTheEntryAnimation() {
        val gate = NativePayRevealGate(false)
        gate.frame(10)
        assertNull(gate.remaining(1000))
        gate.page(1000, true, false)
        assertNull(gate.remaining(2000))
        gate.frame(1100)
        assertFalse(gate.reveal(1649))
        assertTrue(gate.reveal(1650))
    }
    @Test fun movingFramesExtendTheMaskUntilThePageIsQuiet() {
        val gate = NativePayRevealGate(false)
        gate.page(0, true, false)
        for (time in 0L..1000L step 16) { gate.frame(time); assertFalse(gate.reveal(time)) }
        gate.frame(1000)
        assertFalse(gate.reveal(1179)); assertTrue(gate.reveal(1180))
        gate.frame(1200)
        assertTrue("QR refresh must not restart the loading mask", gate.reveal(1200))
    }
    @Test fun scannerCanRevealContinuousCameraFramesButPaymentCannot() {
        for (camera in listOf(false, true)) {
            val gate = NativePayRevealGate(camera)
            gate.page(0, true, false)
            gate.frame(650)
            assertEquals(camera, gate.reveal(650))
        }
    }
    @Test fun newPageInvalidatesTheOldFrameAndPendingReveal() {
        val gate = NativePayRevealGate(false)
        gate.page(0, true, false); gate.frame(20)
        gate.page(640, true, false)
        assertNull(gate.remaining(650)); assertFalse(gate.reveal(2000))
        gate.frame(2010)
        assertFalse(gate.reveal(2189)); assertTrue(gate.reveal(2190))
    }
    @Test fun staticPageDrawnBeforeTheDelayedTaskNotificationDoesNotNeedAnotherBuffer() {
        val gate = NativePayRevealGate(false)
        gate.frame(1000)
        assertNull(gate.remaining(1200))
        gate.page(1300, true, false)
        assertFalse(gate.reveal(1949)); assertTrue(gate.reveal(1950))
        val replacement = NativePayRevealGate(false)
        replacement.page(2000, true, false)
        assertFalse("A replacement display cannot inherit the previous QR frame", replacement.reveal(5000))
    }
    @Test fun schemeLauncherAndUnknownPagesDoNotRevealEarly() {
        val gate = NativePayRevealGate(false)
        gate.page(0, false, true); gate.frame(100)
        assertNull(gate.remaining(5000)); assertFalse(gate.reveal(5000))
        gate.page(5000, false, false); gate.frame(5100)
        assertFalse(gate.reveal(6299)); assertTrue(gate.reveal(6300))
    }
}
