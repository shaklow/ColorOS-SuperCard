package dev.local.supercardhost

import android.database.ContentObserver
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.function.BiConsumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingCardScopeTest {
    private class Registry : FloatingCardScope.Observation {
        var registrations = 0; var removals = 0
        var failRegister = false; var failUnregister = false
        val observers = mutableListOf<ContentObserver>()
        override fun register(observer: ContentObserver) {
            registrations++; observers.add(observer)
            if (failRegister) throw IllegalStateException("registration fixture")
        }
        override fun unregister(observer: ContentObserver) {
            removals++
            if (failUnregister) throw IllegalStateException("removal fixture")
        }
    }

    @Test fun repeatedAttachAndDetachOwnExactlyOneRegistration() {
        val errors = mutableListOf<String>()
        val scope = FloatingCardScope(BiConsumer { stage, _ -> errors.add(stage) })
        val registry = Registry(); var changes = 0
        assertTrue(scope.attachObservation(registry, Runnable { changes++ }))
        repeat(3) { assertFalse(scope.attachObservation(registry, Runnable { fail("duplicate observer") })) }
        assertEquals(1, registry.registrations)
        registry.observers.single().dispatchChange(false); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        repeat(3) { scope.detach() }
        assertEquals(1, registry.removals); assertFalse(scope.isAttached); assertNull(scope.token())
        assertTrue(errors.isEmpty())
    }

    @Test fun oldCallbacksAndWorkerRepliesCannotCrossAReattachment() {
        val scope = FloatingCardScope(BiConsumer { _, error -> throw error })
        val registry = Registry(); var changes = 0; var work = 0
        scope.attachObservation(registry, Runnable { changes++ })
        val old = scope.token()
        scope.post(Runnable { fail("queued work from detached card") })
        scope.postDelayed(Runnable { fail("detached countdown") }, 1000)
        registry.observers[0].dispatchChange(false)
        scope.detach()
        assertFalse(scope.postFor(old, Runnable { fail("late worker reply") }))
        scope.attachObservation(registry, Runnable { changes++ })
        registry.observers[0].dispatchChange(false) // Even a notification delivered after unregister is stale.
        assertFalse(scope.postFor(old, Runnable { fail("old reply after reattach") }))
        assertTrue(scope.postFor(scope.token(), Runnable { work++ }))
        registry.observers[1].dispatchChange(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, work); assertEquals(1, changes)
        scope.detach(); assertEquals(2, registry.removals)
    }

    @Test fun repeatedRefreshesCoalesceAndCancelledTimersDoNotRun() {
        val scope = FloatingCardScope(BiConsumer { _, error -> throw error })
        scope.attachObservation(Registry(), Runnable {})
        var refreshes = 0
        val refresh = Runnable { refreshes++ }
        repeat(10) { scope.post(refresh) }
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, refreshes)
        scope.postDelayed(refresh, 1000); scope.remove(refresh)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertEquals(1, refreshes)
        scope.postDelayed(refresh, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(2, refreshes)
        scope.detach()
    }

    @Test fun observationFailuresDoNotKeepWorkAliveOrPreventRetry() {
        val errors = mutableListOf<String>()
        val scope = FloatingCardScope(BiConsumer { stage, _ -> errors.add(stage) })
        val registry = Registry().apply { failRegister = true }
        scope.attachObservation(registry, Runnable { fail("failed old registration") })
        scope.detach(); assertEquals(0, registry.removals)
        registry.failRegister = false; registry.failUnregister = true
        scope.attachObservation(registry, Runnable { fail("stale failed-removal observer") })
        scope.postDelayed(Runnable { fail("cleanup must cancel work even when unregister fails") }, 1)
        scope.detach()
        registry.observers.forEach { it.dispatchChange(false) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(scope.isAttached); assertEquals(listOf("register", "unregister"), errors)
        registry.failUnregister = false
        assertTrue(scope.attachObservation(registry, Runnable {})); scope.detach()
        assertEquals(3, registry.registrations); assertEquals(2, registry.removals)
    }
}
