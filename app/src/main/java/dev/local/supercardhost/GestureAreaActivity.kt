package dev.local.supercardhost

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import java.util.function.Consumer

/** One app-owned page; sidebar controls, resources and preference writes are no longer loaded. */
class GestureAreaActivity : M3eSettingsActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var surface: View? = null
    private lateinit var session: FloatSettingsSession
    private var message = "正在连接卡包服务…"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "手势与唤出"
        val requester = applicationContext
        session = FloatSettingsSession(
            { operation, values, result -> CardConfiguration.request(requester, operation, values, result::complete) },
            object : FloatSettingsSession.Scheduler {
                override fun now() = SystemClock.elapsedRealtime()
                override fun epoch() = SystemClock.elapsedRealtimeNanos()
                override fun execute(action: Runnable) { main.post(action) }
                override fun after(action: Runnable, delay: Long) { main.postDelayed(action, delay) }
                override fun cancel(action: Runnable) { main.removeCallbacks(action) }
            }, object : FloatSettingsSession.Listener {
                override fun changed() {
                    message = if (session.state().enabled) "调整后自动保存" else "超级卡包总开关已关闭"
                    render()
                }
                override fun failed(text: String, error: String?) {
                    message = text
                    android.util.Log.w("SuperCardGestureSettings", "$text: $error")
                    if (alive()) Toast.makeText(this@GestureAreaActivity, text, Toast.LENGTH_LONG).show()
                    render()
                }
            })
        try {
            surface = DhuMaterialUi.gestureSettings(this)
            setContentView(surface)
            DhuMaterialUi.bindGestureSettings(surface, Consumer(::action))
            render()
        } catch (error: Exception) { surface = null; unavailable(error) }
        catch (error: LinkageError) { surface = null; unavailable(error) }
    }
    override fun onResume() {
        super.onResume()
        surface?.let { DhuMaterialUi.bindGestureSettings(it, Consumer(::action)); session.resume() }
    }
    override fun onPause() { session.pause(); super.onPause() }
    override fun onDestroy() { session.close(); super.onDestroy() }

    private fun action(event: Bundle) {
        when (event.getString("action")) {
            "back" -> finish()
            "retry" -> session.query()
            "position" -> session.range(0, event.getInt("value"))
            "length" -> session.range(1, event.getInt("value"))
            "opacity" -> session.opacity(event.getInt("value") / 1000f)
            "range" -> session.range(event.getFloat("top"), event.getFloat("bottom"))
            "tracking" -> session.startTracking(event.getInt("kind"))
            "commit" -> session.stopTracking()
            "handleVisible" -> session.visible(event.getBoolean("value"))
            "handleAutoHide" -> session.autoHide(event.getBoolean("value"))
            "unlocked" -> session.unlocked(event.getBoolean("value"))
            "lockStyle" -> session.lockStyle(event.getInt("value"))
            "reset" -> session.reset()
        }
    }
    private fun render() {
        val root = surface ?: return
        val values = session.snapshot().apply { putString("message", message) }
        DhuMaterialUi.renderGestureSettings(root, values)
    }
}
