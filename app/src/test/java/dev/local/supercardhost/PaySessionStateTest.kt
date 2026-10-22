package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class PaySessionStateTest {
    @Test fun reusedManagerAndPageStillInvalidateOldTaskAndSdkWork() {
        val state = PaySessionState(); val manager = Any()
        val first = state.begin(manager, "wx_pay", 999)
        val next = state.begin(manager, "wx_pay", 0)
        assertFalse(state.isCurrent(first)); assertTrue(state.isCurrent(next))
        assertEquals(first.generation + 1, next.generation)
        assertEquals(0, next.user); assertSame(manager, next.manager)
        assertFalse(state.clear(first)); assertSame(next, state.snapshot)
        assertTrue(state.clear(next)); assertFalse(state.isCurrent(next))
        assertNull(state.snapshot.manager); assertNull(state.snapshot.function)
        assertEquals(-1, state.snapshot.user); assertEquals(next.generation + 1, state.snapshot.generation)
    }

    @Test fun taskWorkerCannotSeeAnAccountFromADifferentLaunch() {
        val state = PaySessionState(); val owners = arrayOf(Any(), Any())
        val start = CountDownLatch(1); val error = AtomicReference<Throwable>()
        val writer = Thread {
            try { start.await(); repeat(20_000) { val user = it % 2; state.begin(owners[user], "$user", user) } }
            catch (failure: Throwable) { error.set(failure) }
        }
        writer.start(); start.countDown()
        do {
            val snapshot = state.snapshot
            if (snapshot.manager != null) {
                assertEquals(snapshot.user.toString(), snapshot.function)
                assertSame(owners[snapshot.user], snapshot.manager)
            }
        } while (writer.isAlive)
        writer.join(); error.get()?.let { throw AssertionError("Session publication failed", it) }
    }
}
