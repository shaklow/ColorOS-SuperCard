package dev.local.supercardhost

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Existing launcher component now opens the settings hub; OEM card management stays reachable. */
class HostActivity : M3eSettingsActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "超级卡包"
        if (!showPage("home")) return
        ui.on("open_cards") { open("com.vivo.card.setting.CardSettingActivity") }
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
