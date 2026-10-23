package dev.local.supercardhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.function.Consumer

/** Owns the runtime's receivers and periodic synchronization; closing cancels every owned callback. */
internal class RuntimeEventLoop(
    private val context: Context, private val update: Runnable, private val hide: Runnable,
    private val reveal: Runnable, private val debug: Consumer<String>, private val dump: Runnable
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var closed = false
    private var stateRegistered = false
    private var debugRegistered = false
    private val tick = object : Runnable {
        override fun run() { if (active) { safely(update); if (active) main.postDelayed(this, 750) } }
    }
    private val initialDump = Runnable { if (active) safely(dump) }
    private val state = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active) return
            if (intent.action == Intent.ACTION_SCREEN_OFF) safely(hide)
            safely(update)
            if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) safely(reveal)
        }
    }
    private val diagnostic = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (active) safely(Runnable { debug.accept(intent.getStringExtra("command") ?: "dump") })
        }
    }
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed) { "Runtime event loop is closed" }
        if (active) return
        try {
            context.registerReceiver(diagnostic, IntentFilter("dev.local.supercardhost.DEBUG"), "android.permission.DUMP", main, Context.RECEIVER_EXPORTED)
            debugRegistered = true
            context.registerReceiver(state, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }, null, main, Context.RECEIVER_EXPORTED)
            stateRegistered = true; active = true
            main.postDelayed(tick, 750); main.postDelayed(initialDump, 2000)
        } catch (error: Throwable) { close(); throw error }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true; active = false; main.removeCallbacks(tick); main.removeCallbacks(initialDump)
        if (stateRegistered) { stateRegistered = false; try { context.unregisterReceiver(state) } catch (_: IllegalArgumentException) { } }
        if (debugRegistered) { debugRegistered = false; try { context.unregisterReceiver(diagnostic) } catch (_: IllegalArgumentException) { } }
    }
    private fun safely(action: Runnable) { try { action.run() } catch (error: Throwable) { Log.e("SuperCardRuntime", "Runtime event failed", error) } }
}
