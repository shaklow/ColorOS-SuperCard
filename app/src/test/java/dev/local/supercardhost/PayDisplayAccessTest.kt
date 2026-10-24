package dev.local.supercardhost

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.function.BiConsumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class PayDisplayAccessTest {
    class Manager {
        var enabled = true; var id = 7; var currentTask = -1; var releases = 0
        var read: Runnable? = null; var rejectResume = false; var resumes = 0
        fun isVirtualDisplayActive() = enabled
        fun getVirtualDisplayId(): Int { read?.run(); return id }
        fun getTaskId() = currentTask
        fun setTaskId(id: Int) { currentTask = id }
        fun handleActivityResumed(component: ComponentName) {
            resumes++; currentTask = 1 // The OEM callback can observe a main-display task.
            if (rejectResume) throw IllegalStateException(component.className)
        }
        fun release() { releases++ }
    }
    private fun access(state: PaySessionState) = PayDisplayAccess(Manager::class.java, state,
        BiConsumer { _, error -> throw AssertionError(error) })

    @Test fun rejectsInactiveMainAndReplacedDisplays() {
        val state = PaySessionState(); val manager = Manager(); val reader = access(state)
        val session = state.begin(manager, "wx_pay", 999)
        assertEquals(7, reader.id(session))
        manager.id = 0; assertEquals(-1, reader.id(session))
        manager.id = 7; manager.enabled = false; assertEquals(-1, reader.id(session))
        manager.enabled = true
        manager.read = Runnable { state.begin(Manager(), "wx_pay", 0) }
        assertEquals(-1, reader.id(session)) // Relaunch during the reflective read is stale too.
        assertEquals(-1, reader.id(session))
    }

    @Test fun correctsTheDisplayTaskEvenIfTheOemCallbackFails() {
        val reader = access(PaySessionState()); val manager = Manager(); var notified = 0
        val page = ComponentName("com.tencent.mm", "PayPage")
        reader.notifyResumed(manager, page, 7, true, Runnable { notified++ })
        assertEquals(7, manager.currentTask); assertEquals(1, notified)
        manager.rejectResume = true
        try { reader.notifyResumed(manager, page, 8, true, Runnable { notified++ }); fail("resume error") }
        catch (_: java.lang.reflect.InvocationTargetException) {}
        assertEquals(8, manager.currentTask); assertEquals(1, notified)
        reader.notifyResumed(manager, page, 9, false, Runnable { fail("duplicate resume") })
        assertEquals(9, manager.currentTask); assertEquals(2, manager.resumes)
    }

    @Test fun releasingAnInvalidatedAccountDoesNotReleaseItsReplacement() {
        val state = PaySessionState(); val reader = access(state)
        val previous = Manager(); val next = Manager()
        val old = state.begin(previous, "wx_pay", 999)
        assertTrue(state.clear(old))
        state.begin(next, "wx_pay", 0)
        reader.release(old.manager!!)
        assertEquals(1, previous.releases); assertEquals(0, next.releases)
        assertEquals(7, reader.id(state.snapshot))
    }
    @Test fun nativeOwnerUsesTheSameIdentityChecksWithoutOemReflection() {
        val state = PaySessionState(); val reader = access(state)
        var closed = false; var observedTask = -1
        val owner = object : NativePayDisplayOwner {
            override val displayId get() = if (closed) -1 else 12
            override fun resumed(component: ComponentName, task: Int) { observedTask = task }
            override fun close() { closed = true }
        }
        val native = state.begin(owner, "wx_pay", 991)
        assertEquals(12, reader.id(native))
        reader.notifyResumed(owner, ComponentName("com.tencent.mm", "Wallet"), 31, true, Runnable {})
        assertEquals(31, observedTask)
        state.begin(Manager(), "wx_pay", 0)
        assertEquals(-1, reader.id(native))
        reader.release(owner)
        assertTrue(closed); assertEquals(7, reader.id(state.snapshot))
    }
}
