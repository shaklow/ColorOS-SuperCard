package dev.local.supercardhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class RuntimeEventLoopTest {
    private class Host : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val registrations = linkedMapOf<BroadcastReceiver, Pair<IntentFilter, String?>>()
        var count = 0; var rejectSecond = false
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, handler: Handler?, flags: Int): Intent? {
            count++
            if (rejectSecond && count == 2) throw IllegalStateException("Rejected receiver")
            assertEquals(Context.RECEIVER_EXPORTED, flags)
            registrations[receiver!!] = filter to permission
            return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) { check(registrations.remove(receiver) != null) }
    }
    @Test fun duplicateStartHasOneLoopAndClosingReleasesAllOwnedWork() {
        val host = Host(); var updates = 0; var hides = 0; var reveals = 0; var dumps = 0
        val commands = mutableListOf<String>()
        val loop = RuntimeEventLoop(host, { updates++ }, { hides++ }, { reveals++ }, { commands.add(it) }, { dumps++ })
        loop.start(); loop.start(); assertEquals(2, host.count)
        val debug = host.registrations.entries.single { it.value.first.hasAction("dev.local.supercardhost.DEBUG") }
        assertEquals("android.permission.DUMP", debug.value.second)
        val state = host.registrations.entries.single { it.value.first.hasAction(Intent.ACTION_SCREEN_OFF) }.key
        state.onReceive(host, Intent(Intent.ACTION_SCREEN_OFF)); assertEquals(1, hides); assertEquals(1, updates)
        state.onReceive(host, Intent(Intent.ACTION_USER_PRESENT)); assertEquals(1, reveals)
        debug.key.onReceive(host, Intent().putExtra("command", "show")); assertEquals(listOf("show"), commands)
        ShadowLooper.idleMainLooper(750, TimeUnit.MILLISECONDS); assertEquals(3, updates)
        loop.close(); loop.close(); assertTrue(host.registrations.isEmpty())
        ShadowLooper.idleMainLooper(3000, TimeUnit.MILLISECONDS); assertEquals(3, updates); assertEquals(0, dumps)
        state.onReceive(host, Intent(Intent.ACTION_SCREEN_OFF)); debug.key.onReceive(host, Intent().putExtra("command", "show"))
        assertEquals(1, hides); assertEquals(1, commands.size)
    }
    @Test fun partialReceiverRegistrationRollsBackBeforeSchedulingWork() {
        val host = Host().apply { rejectSecond = true }; var updates = 0
        val loop = RuntimeEventLoop(host, { updates++ }, {}, {}, {}, {})
        try { loop.start(); fail("Partial registration succeeded") } catch (_: IllegalStateException) { }
        assertTrue(host.registrations.isEmpty())
        ShadowLooper.idleMainLooper(5000, TimeUnit.MILLISECONDS); assertEquals(0, updates)
    }
}
