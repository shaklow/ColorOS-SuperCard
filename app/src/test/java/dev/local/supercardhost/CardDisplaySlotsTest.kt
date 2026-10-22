package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class CardDisplaySlotsTest {
    @Test fun cameraCanOpenWhilePayIsRetainedAndCannotReplaceIt() {
        val slots = CardDisplaySlots<Any>(); val pay = Any(); val camera = Any()
        slots.add("SuperCard_Virtual", 7, pay)
        slots.add("SuperCard_Lens", 8, camera)
        try { slots.add("SuperCard_Virtual", 9, Any()); fail("PAY cannot be replaced") }
        catch (_: IllegalStateException) {}
        assertSame(camera, slots.remove(8)); assertSame(pay, slots.remove(7))
        assertNull(slots.remove(7))
    }

    @Test fun unknownNamesAndIdsCannotAffectAnOwnedDisplay() {
        val slots = CardDisplaySlots<Any>(); val pay = Any()
        slots.add("SuperCard_Virtual", 7, pay)
        try { slots.add("OtherDisplay", 8, Any()); fail("arbitrary name") } catch (_: IllegalArgumentException) {}
        try { slots.remove(8); fail("foreign ID") } catch (_: IllegalArgumentException) {}
        try { slots.add("SuperCard_Lens", 7, Any()); fail("duplicate ID") } catch (_: IllegalArgumentException) {}
        assertSame(pay, slots.remove(7))
    }

    @Test fun failedCameraCreationDoesNotConsumeItsSlotOrDiscardPay() {
        val slots = CardDisplaySlots<Any>(); val pay = Any(); val camera = Any()
        slots.add("SuperCard_Virtual", 7, pay)
        slots.requireAvailable("SuperCard_Lens") // System creation can fail before add.
        slots.requireAvailable("SuperCard_Lens")
        slots.add("SuperCard_Lens", 8, camera)
        assertEquals(setOf(pay, camera), slots.drain().toSet())
        assertTrue(slots.drain().isEmpty())
        slots.add("SuperCard_Lens", 9, camera); assertSame(camera, slots.remove(9))
    }
}
