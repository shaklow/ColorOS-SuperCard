package dev.local.supercardhost

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast

/** Desktop and LSPosed manage settings; gestures continue to use the original floating window. */
class HostActivity : M3eSettingsActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "超级卡包"
        if (!showPage("home")) return
        ui.on("show_cards") {
            val button = ui.view<View>("show_cards")
            button.isEnabled = false
            CardConfiguration.request(this, "openLegacyCards", null) { _, error ->
                if (alive()) {
                    button.isEnabled = true
                    if (error == null) finish()
                    else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
        ui.on("native_cards") {
            val button = ui.view<View>("native_cards")
            button.isEnabled = false
            CardConfiguration.request(this, "openNativeCards", null) { _, error ->
                if (alive()) {
                    button.isEnabled = true
                    if (error == null) finish()
                    else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
        ui.on("open_cards") { open("$packageName.CardManagementActivity") }
        ui.on("open_runtime") { open("$packageName.HostToolsActivity") }
        ui.on("open_campus") { open("$packageName.DhuPaySettingsActivity") }
        ui.on("open_gestures") { open("$packageName.GestureAreaActivity") }
    }

    override fun onResume() {
        super.onResume()
        if (!isUiReady()) return
        val status = try { contentResolver.call(DhuPayProvider.URI, "status", null, null) }
        catch (_: RuntimeException) { null }
        ui.render("campus_summary" to when {
            status == null -> "授权状态暂不可用，点击查看"
            status.getBoolean("configured") -> "已配置授权链接，点击管理"
            else -> "添加付款码授权链接"
        })
    }

    private fun open(component: String) {
        try { startActivity(Intent().setClassName(packageName, component)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "页面暂不可用，请更新超级卡包", Toast.LENGTH_SHORT).show() }
    }
}
