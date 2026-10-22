package dev.local.supercardhost

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import java.util.concurrent.Executors

/** Credentials stay in the existing provider. No URL or code is accepted from external Intents. */
class DhuPaySettingsActivity : M3eSettingsActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var url: EditText
    private var generation = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = "东华校园卡设置"
        if (!showPage("campus")) return
        url = ui.view("url")
        val stored = call("readUrl")
        url.setText(stored?.getString("url", "") ?: "")
        message(when {
            stored == null -> "服务暂不可用，请重新打开此页。"
            stored.getBoolean("configured") -> "已配置授权链接"
            else -> "尚未配置授权链接"
        })
        ui.on("save") {
            val value = try { DhuPayProtocol.authorizationUri(url.text.toString()).toString() }
            catch (invalid: IllegalArgumentException) {
                ui.render("url_error" to invalid.message); return@on
            }
            ui.render("url_error" to null)
            save(value, verify = true)
        }
        ui.on("verify") { verify() }
        ui.on("clear") {
            ui.confirm("清除授权链接？", "清除后，校园卡将停止获取付款码。") {
                if (alive()) save("", verify = false)
            }
        }
    }

    private fun call(method: String, argument: String? = null): Bundle? = try {
        contentResolver.call(DhuPayProvider.URI, method, argument, null)
    } catch (_: RuntimeException) { null }

    private fun save(value: String, verify: Boolean) {
        generation++
        ui.view<Button>("verify").isEnabled = true
        val result = call("save", value)
        if (result == null || result.containsKey("error")) {
            message(result?.getString("message") ?: "保存失败，请重试"); return
        }
        if (value.isEmpty()) { url.setText(""); ui.render("url_error" to null) }
        message(if (value.isEmpty()) "授权链接已清除" else "已保存，正在验证…")
        if (verify) verify()
    }

    private fun verify() {
        val expected = ++generation
        message("正在连接校园卡服务…")
        ui.view<Button>("verify").isEnabled = false
        worker.execute {
            val reply = call("fetch")
            runOnUiThread {
                if (!alive() || expected != generation) return@runOnUiThread
                ui.view<Button>("verify").isEnabled = true
                message(when {
                    reply == null -> "服务暂不可用，请重试"
                    reply.containsKey("error") -> reply.getString("message") ?: "验证失败，请更新授权链接"
                    else -> "验证成功，可以在卡包中使用东华校园卡"
                })
                // A settings verification can refresh a visible code; notify its existing observer.
                if (reply != null && !reply.containsKey("error")) contentResolver.notifyChange(DhuPayProvider.URI, null)
            }
        }
    }

    private fun message(value: String) { ui.render("message" to value) }
    override fun onDestroy() { generation++; worker.shutdownNow(); super.onDestroy() }
}
