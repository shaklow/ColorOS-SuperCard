package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class FloatPreviewLeaseTest {
    @Test fun oldPagesCannotReplaceOrClearTheCurrentPreview() {
        val lease = FloatPreviewLease()
        assertTrue(lease.preview("first", 10, 1, 100, 1000, 6000))
        assertTrue(lease.preview("second", 20, 1, 100, 1000, 6000))
        assertFalse(lease.end("first", 10, 1, 100))
        assertFalse(lease.preview("first", 10, 1, 100, 1000, 6000))
        assertFalse(lease.end("second", 20, 2, 100))
        assertFalse(lease.preview("other", 20, 1, 100, 1000, 6000))
        assertTrue(lease.end("second", 20, 1, 100))
        assertFalse(lease.preview("second", 20, 1, 100, 1000, 6000))
        assertFalse(lease.end("second", 20, 1, 100))
    }
    @Test fun endBeforeDeliveryLeavesATombstoneAndExpiredRequestsCannotClaimOwnership() {
        val lease = FloatPreviewLease()
        assertTrue(lease.end("closed", 10, 1, 100))
        assertFalse(lease.preview("closed", 10, 1, 100, 1000, 6000))
        for ((owner, epoch, deadline) in listOf(Triple("late", 20L, 1000L), Triple("future", 200L, 6000L), Triple("", 20L, 6000L), Triple("late", 20L, 7000L))) {
            try { lease.preview(owner, epoch, 1, 100, 1000, deadline); fail("Invalid preview accepted") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(lease.preview("current", 20, 1, 100, 1000, 6000))
    }
}
