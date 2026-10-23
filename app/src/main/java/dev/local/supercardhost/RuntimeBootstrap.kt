package dev.local.supercardhost

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Process bootstrap adapter. Application creation and duplicate notifications share one transaction. */
internal object RuntimeBootstrap {
    private val main = Handler(Looper.getMainLooper())
    private var session: RuntimeBootstrapSession? = null

    @JvmStatic fun schedule(application: Application) {
        if (application.packageName != "com.android.systemui") return
        if (!main.post {
            if (session == null) session = RuntimeBootstrapSession(object : RuntimeBootstrapSession.Scheduler {
                override fun post(action: Runnable, delay: Long) = main.postDelayed(action, delay)
                override fun cancel(action: Runnable) = main.removeCallbacks(action)
            }, object : RuntimeBootstrapSession.Driver {
                override fun prepare(): Runnable {
                    val module = application.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY)
                    NativeCardRuntime.install(application)
                    return PluginRuntime.prepare(application, module)
                }
                override fun changed() {
                    val current = session ?: return
                    Log.i("SuperCardRuntime", "Startup ${current.phase}, attempt=${current.attempts}, requiresRestart=${current.requiresRestart}")
                }
                override fun failed(phase: String, error: Throwable) {
                    if (phase == "attach") PluginRuntime.abortStartup()
                    Log.e("SuperCardRuntime", "Startup $phase failed", error)
                }
            })
            session?.request()
        }) Log.e("SuperCardRuntime", "SystemUI main thread rejected startup scheduling")
    }
    @JvmStatic fun snapshot() = Bundle().apply {
        val current = session
        putString("phase", current?.phase?.name ?: "IDLE")
        putInt("attempts", current?.attempts ?: 0)
        putBoolean("requiresRestart", current?.requiresRestart ?: false)
        putString("error", current?.lastError)
    }
}
