package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class RuntimeBootstrapSessionTest {
    private class Clock : RuntimeBootstrapSession.Scheduler {
        var now = 0L; var reject = false
        val tasks = linkedMapOf<Runnable, Long>()
        override fun post(action: Runnable, delay: Long): Boolean {
            if (reject) return false
            tasks[action] = now + delay; return true
        }
        override fun cancel(action: Runnable) { tasks.remove(action) }
        fun advance(delay: Long = 0) {
            val end = now + delay
            while (true) {
                val next = tasks.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
                now = next.value; tasks.remove(next.key); next.key.run()
            }
            now = end
        }
    }
    @Test fun duplicateRequestsShareOneAttachAndTransientPreparationCanRecover() {
        val clock = Clock(); var preparations = 0; var attaches = 0
        val session = RuntimeBootstrapSession(clock, object : RuntimeBootstrapSession.Driver {
            override fun prepare(): Runnable {
                if (++preparations < 3) throw IllegalStateException("Package not ready")
                return Runnable { attaches++ }
            }
        })
        repeat(20) { session.request() }; clock.advance()
        assertEquals(1, preparations); assertEquals(1, clock.tasks.size)
        repeat(20) { session.request() }; clock.advance(999); assertEquals(1, preparations)
        clock.advance(1); assertEquals(2, preparations)
        clock.advance(3000); assertEquals(1, attaches)
        assertEquals(RuntimeBootstrapSession.Phase.RUNNING, session.phase)
        assertFalse(session.requiresRestart); assertNull(session.lastError)
        repeat(20) { session.request() }; clock.advance(60_000)
        assertEquals(3, preparations); assertEquals(1, attaches); assertTrue(clock.tasks.isEmpty())
    }
    @Test fun failuresAfterHookBoundaryCannotRepeatAttachment() {
        val clock = Clock(); var attaches = 0
        val session = RuntimeBootstrapSession(clock, object : RuntimeBootstrapSession.Driver {
            override fun prepare() = Runnable { attaches++; throw IllegalStateException("Partial hook") }
        })
        session.request(); clock.advance(); repeat(10) { session.request() }; clock.advance(60_000)
        assertEquals(1, attaches); assertTrue(session.requiresRestart)
        assertEquals(RuntimeBootstrapSession.Phase.FAILED, session.phase); assertTrue(clock.tasks.isEmpty())
    }
    @Test fun preparationRetriesAreBoundedAndExplicitRequestsCanStartANewPreparationCycle() {
        val clock = Clock(); var prepare = 0
        val session = RuntimeBootstrapSession(clock, object : RuntimeBootstrapSession.Driver {
            override fun prepare(): Runnable { prepare++; throw IllegalStateException("Missing archive") }
        })
        session.request(); clock.advance(60_000)
        assertEquals(5, prepare); assertEquals(RuntimeBootstrapSession.Phase.FAILED, session.phase)
        assertFalse(session.requiresRestart); assertTrue(clock.tasks.isEmpty())
        session.request(); clock.advance(); assertEquals(6, prepare)
        session.close(); clock.advance(60_000); assertEquals(6, prepare)
    }
    @Test fun cancellationAndLateQueuedActionsCannotResurrectStartup() {
        val clock = Clock(); var prepare = 0
        val session = RuntimeBootstrapSession(clock, object : RuntimeBootstrapSession.Driver {
            override fun prepare(): Runnable { prepare++; return Runnable { fail("Closed startup attached") } }
        })
        session.request(); val old = clock.tasks.keys.single(); session.close(); old.run()
        session.request(); assertEquals(0, prepare); assertTrue(clock.tasks.isEmpty())
        assertEquals(RuntimeBootstrapSession.Phase.CLOSED, session.phase)
    }
    @Test fun rejectedMainQueueDoesNotWedgeStartupAndPrepareCallbacksCanCloseIt() {
        val clock = Clock().apply { reject = true }; var attaches = 0
        lateinit var session: RuntimeBootstrapSession
        session = RuntimeBootstrapSession(clock, object : RuntimeBootstrapSession.Driver {
            override fun prepare(): Runnable { session.close(); return Runnable { attaches++ } }
        })
        session.request(); assertEquals(RuntimeBootstrapSession.Phase.FAILED, session.phase)
        clock.reject = false; session.request(); clock.advance()
        assertEquals(0, attaches); assertEquals(RuntimeBootstrapSession.Phase.CLOSED, session.phase)
    }
}
