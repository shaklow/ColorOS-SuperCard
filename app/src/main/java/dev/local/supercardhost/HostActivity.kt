package dev.local.supercardhost

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.widget.CompoundButton

/** LSPosed settings entry and the card toolbar destination. */
class HostActivity : M3eSettingsActivity() {
    private var queryGeneration = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "超级卡包"
        if (!showPage("home")) return
        ui.on("open_native") { open("$packageName.NativeCardsActivity") }
        ui.on("open_cards") { open("com.vivo.card.setting.CardSettingActivity") }
        ui.on("open_runtime") { open("$packageName.HostToolsActivity") }
        ui.on("open_campus") { open("$packageName.DhuPaySettingsActivity") }
        ui.on("open_gestures") { open("$packageName.GestureAreaActivity") }
    }

    override fun onResume() {
        super.onResume()
        if (!isUiReady()) return
        onResumeSettings()
    }

    private fun onResumeSettings() {
        val expected = ++queryGeneration
        val nativeSwitch = ui.view<CompoundButton>("toggle_native_cards")
        nativeSwitch.setOnCheckedChangeListener(null)
        nativeSwitch.isEnabled = false
        CardConfiguration.request(this, "query", null) { result, error ->
            if (!alive() || expected != queryGeneration) return@request
            nativeSwitch.setOnCheckedChangeListener(null)
            nativeSwitch.isChecked = error == null && result?.getBoolean("nativeCards", false) == true
            nativeSwitch.isEnabled = error == null
            nativeSwitch.setOnCheckedChangeListener { _, checked ->
                nativeSwitch.isEnabled = false
                CardConfiguration.request(this, "nativeCards", Bundle().apply { putBoolean("value", checked) }) { saved, failure ->
                    if (alive() && expected == queryGeneration) {
                        nativeSwitch.setOnCheckedChangeListener(null)
                        nativeSwitch.isChecked = saved?.getBoolean("nativeCards", false) == true
                        nativeSwitch.isEnabled = true
                        if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_SHORT).show()
                        // Rebind after the confirmed readback, including a failed save.
                        onResumeSettings()
                    }
                }
            }
        }
        val status = try { contentResolver.call(DhuPayProvider.URI, "status", null, null) }
        catch (_: RuntimeException) { null }
        ui.render("campus_summary" to when {
            status == null -> "授权状态暂不可用，点击查看"
            status.getBoolean("configured") -> "已配置授权链接，点击管理"
            else -> "添加付款码授权链接"
        })
    }

    override fun onPause() { queryGeneration++; super.onPause() }

    private fun open(component: String) {
        try { startActivity(Intent().setClassName(packageName, component)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "页面暂不可用，请更新超级卡包", Toast.LENGTH_SHORT).show() }
    }
}
