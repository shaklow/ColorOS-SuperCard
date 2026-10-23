package dev.local.supercardhost

import android.app.Application
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** The native core's narrow boundary to real ColorOS/SystemUI authentication. */
internal class ColorOsCardAccess(private val application: Application) {
    private val main = Handler(Looper.getMainLooper())
    fun unlocked(): Boolean {
        val keyguard = application.getSystemService(KeyguardManager::class.java) ?: return false
        val power = application.getSystemService(PowerManager::class.java) ?: return false
        return power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    }
    fun requestUnlock(ready: Runnable): AutoCloseable {
        val attempt = Attempt(ready)
        try { attempt.start() } catch (error: Throwable) { attempt.close(); throw error }
        return attempt
    }
    private inner class Attempt(private val ready: Runnable) : AutoCloseable {
        private var closed = false
        private var verifying = false
        private var deadline = 0L
        private val timeout = Runnable { close() }
        private val verify = object : Runnable {
            override fun run() {
                if (closed) return
                if (unlocked()) { close(); ready.run() }
                else if (SystemClock.uptimeMillis() < deadline) main.postDelayed(this, 100)
                else close()
            }
        }
        fun start() {
            val starter = SystemUiBridge.dependency(application, "com.android.systemui.plugins.ActivityStarter")
                ?: error("系统解锁入口暂不可用")
            val api = application.classLoader.loadClass("com.android.systemui.plugins.ActivityStarter")
            val execute = api.getMethod("executeRunnableDismissingKeyguard", Runnable::class.java, Runnable::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val success = Runnable { main.post {
                if (!closed && !verifying) { verifying = true; deadline = SystemClock.uptimeMillis() + 2000; verify.run() }
            } }
            val cancel = Runnable { main.post { close() } }
            main.postDelayed(timeout, 30_000)
            execute.invoke(starter, success, cancel, false, true, false)
        }
        override fun close() { closed = true; main.removeCallbacks(timeout); main.removeCallbacks(verify) }
    }
}
