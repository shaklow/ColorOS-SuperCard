package dev.local.supercardhost

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.function.BiConsumer
import java.util.function.Supplier

internal data class PayTaskSnapshot(val display: Int, val task: Int, val user: Int,
    val component: ComponentName, val payment: Boolean)

/** Task ordering, the selected display and account decide which original callback may run. */
internal object PayTaskSelection {
    fun select(tasks: List<PayTaskSnapshot>, display: Int, user: Int): PayTaskSnapshot? =
        if (display > 0) tasks.firstOrNull { it.display == display && it.payment && (user < 0 || it.user == user) }
        else tasks.firstOrNull { it.display == 0 }
}

/** ColorOS task observations in place of vivo's activity observer, scoped to immutable launch snapshots. */
internal class PayTaskObserver(
    private val host: Application,
    private val plugin: Any,
    private val sessions: PaySessionState,
    private val displays: PayDisplayAccess,
    private val touch: Supplier<PayTouchPolicy?>,
    private val failure: BiConsumer<String, Throwable>
) {
    private val tasks = requireNotNull(host.getSystemService(ActivityManager::class.java)) { "ActivityManager unavailable" }
    private val topActivity = PayDisplayAccess.method(plugin.javaClass, "setTopActivity", Void.TYPE,
        String::class.java, String::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Handler? = null
    private var thread: HandlerThread? = null
    @Volatile private var running = false
    private var previousDisplay = -1
    private var lastManager: Any? = null
    private var lastManagerGeneration = -1L
    private var lastManagerDisplay = -1
    private var lastManagerComponent: ComponentName? = null
    private var lastPluginDisplay = -1
    private var lastPluginComponent: ComponentName? = null

    fun start() {
        if (running) return
        val next = HandlerThread("SuperCardPayTasks")
        thread = next; next.start()
        worker = Handler(next.looper); running = true
        worker!!.post(poll)
    }

    fun stop() {
        running = false
        worker?.removeCallbacksAndMessages(null); worker = null
        main.removeCallbacksAndMessages(null)
        thread?.quitSafely(); thread = null
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val session = sessions.snapshot
                val display = displays.id(session)
                if (previousDisplay > 0 && display < 0) main.post {
                    if (running && displays.id(sessions.snapshot) < 0) PluginRuntime.setPaymentSecure(false)
                }
                previousDisplay = display
                val observed = tasks.getRunningTasks(30).mapNotNull { task ->
                    val component = task.topActivity ?: return@mapNotNull null
                    val id = taskDisplay(task)
                    val payment = isPaymentPackage(component.packageName)
                    // Keep the original lazy user lookup: only a candidate on the active
                    // payment display needs the account field, not every main-display task.
                    val user = if (display > 0 && id == display && payment && session.user >= 0)
                        task.javaClass.getField("userId").getInt(task) else -1
                    PayTaskSnapshot(id, task.taskId, user, component, payment)
                }
                PayTaskSelection.select(observed, display, session.user)?.let { selected ->
                    main.post { synchronize(session, display, selected) }
                }
            } catch (error: Throwable) { failure.accept("observe tasks", error) }
            finally { if (running) worker?.postDelayed(this, 300) }
        }
    }

    private fun synchronize(session: PaySessionState.Snapshot, display: Int, selected: PayTaskSnapshot) {
        if (!running || !sessions.isCurrent(session) || displays.id(session) != display) return
        val component = selected.component
        val policy = touch.get()
        policy?.observe(if (display > 0 && selected.display == display) component else null)
        val manager = session.manager
        if (display > 0 && selected.display == display && manager != null) {
            val changed = lastManager !== manager || lastManagerGeneration != session.generation ||
                lastManagerDisplay != display || lastManagerComponent != component
            try {
                displays.notifyResumed(manager, component, selected.task, changed, Runnable {
                    lastManager = manager; lastManagerGeneration = session.generation
                    lastManagerDisplay = display; lastManagerComponent = component
                })
            } catch (error: Throwable) { failure.accept("notify payment resume", error) }
        }
        if (selected.display != lastPluginDisplay || component != lastPluginComponent) {
            if (selected.display == 0) GestureHandle.onTopActivityChanged()
            try {
                topActivity.invoke(plugin, component.className, component.packageName)
                lastPluginDisplay = selected.display; lastPluginComponent = component
            } catch (error: Throwable) { failure.accept("notify original top activity", error) }
        }
        if (display > 0 && selected.display == display) policy?.applyGeneration(session.generation, component)
    }

    /** One bounded snapshot on an actual OEM timeout, never logging each poll. */
    fun reportWeChatFallback(function: String) {
        val handler = worker ?: return
        val display = displays.id(sessions.snapshot)
        val ready = RootDisplayClient.isReady()
        handler.post {
            if (!running) return@post
            try {
                val info = host.packageManager.getPackageInfo("com.tencent.mm", 0)
                val pages = tasks.getRunningTasks(30).filter { it.topActivity?.packageName == "com.tencent.mm" }
                    .take(6).joinToString(", ") { "${it.topActivity!!.className}@display=${taskDisplay(it)}" }
                Log.w("SuperCardPayBridge", "WeChat launch timeout: function=$function version=${info.versionName}/${info.longVersionCode}" +
                    " backendReady=$ready requestedDisplay=$display pages=[$pages]")
            } catch (error: Throwable) { failure.accept("report WeChat launch timeout", error) }
        }
    }

    private fun taskDisplay(task: ActivityManager.RunningTaskInfo): Int = task.javaClass.getField("displayId").getInt(task)
    private fun isPaymentPackage(name: String): Boolean = name == "com.tencent.mm" ||
        name == "com.eg.android.AlipayGphone" || PayDiagnostics.isTestPackage(name)
}
