package dev.local.supercardhost

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.function.BiConsumer

/** One attachment owns one observer and its queued work. No window, credential or View ownership. */
class FloatingCardScope(private val failure: BiConsumer<String, RuntimeException>) {
    class Token internal constructor()
    internal interface Observation {
        fun register(observer: ContentObserver)
        fun unregister(observer: ContentObserver)
    }
    private class Session(val observation: Observation, var changed: Runnable?) {
        val token = Token()
        lateinit var observer: ContentObserver
        var registered = false
        val pending = IdentityHashMap<Runnable, Runnable>()
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var session: Session? = null
    val isAttached: Boolean get() = synchronized(lock) { session != null }
    fun token(): Token? = synchronized(lock) { session?.token }

    fun attach(resolver: ContentResolver, uri: Uri, changed: Runnable): Boolean = attachObservation(object : Observation {
        override fun register(observer: ContentObserver) = resolver.registerContentObserver(uri, false, observer)
        override fun unregister(observer: ContentObserver) = resolver.unregisterContentObserver(observer)
    }, changed)

    internal fun attachObservation(observation: Observation, changed: Runnable): Boolean {
        checkMain()
        val next = synchronized(lock) {
            if (session != null) return false
            Session(observation, changed).also { session = it }
        }
        // A resolver that fails to unregister must not retain the card through its observer.
        val reference = WeakReference(this)
        val token = next.token
        next.observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { reference.get()?.changed(token) }
        }
        try { observation.register(next.observer); next.registered = true }
        catch (error: RuntimeException) { failure.accept("register", error) }
        return true
    }

    fun detach() {
        checkMain()
        val old = synchronized(lock) {
            val previous = session ?: return
            session = null
            previous.changed = null; previous.pending.clear()
            main.removeCallbacksAndMessages(null)
            previous
        }
        if (old.registered) {
            old.registered = false
            try { old.observation.unregister(old.observer) }
            catch (error: RuntimeException) { failure.accept("unregister", error) }
        }
    }

    fun post(action: Runnable): Boolean = schedule(token(), action, 0)
    fun postDelayed(action: Runnable, delayMillis: Long): Boolean = schedule(token(), action, delayMillis)
    fun postFor(token: Token?, action: Runnable): Boolean = schedule(token, action, 0)
    fun remove(action: Runnable) = synchronized(lock) {
        session?.pending?.remove(action)?.let { main.removeCallbacks(it) }
        Unit
    }

    private fun schedule(token: Token?, action: Runnable, delayMillis: Long): Boolean = synchronized(lock) {
        val current = session ?: return false
        if (token == null || current.token !== token) return false
        current.pending.remove(action)?.let { main.removeCallbacks(it) }
        val wrapped = object : Runnable {
            override fun run() {
                val live = synchronized(lock) {
                    if (session !== current || current.pending[action] !== this) false
                    else { current.pending.remove(action); true }
                }
                if (live) action.run()
            }
        }
        current.pending[action] = wrapped
        if (main.postAtTime(wrapped, current.token, SystemClock.uptimeMillis() + delayMillis.coerceAtLeast(0))) true
        else { current.pending.remove(action); false }
    }

    private fun changed(token: Token) {
        val action = synchronized(lock) { session?.takeIf { it.token === token }?.changed }
        action?.run()
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Floating card lifecycle must run on the main thread" }
}
