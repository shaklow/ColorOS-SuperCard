package dev.local.supercardhost

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton

/** Kotlin presentation for the existing authenticated SystemUI diagnostics/settings protocol. */
class HostToolsActivity : M3eSettingsActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var confirmed = Bundle()
    private var confirmedUsers = Bundle()
    private var connected = false
    private var busy = false
    private var rootBusy = false
    private var rendering = false
    private var requestGeneration = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "运行状态与 PAY 设置"
        if (!showPage("runtime")) return
        ui.on("refresh") { checkRoot(true); refreshSetup(true); query() }
        for (app in PayUserSettings.APPS) ui.on("user_$app") { chooseUser(app) }
        for (function in RuntimeSettingsState.functions) {
            ui.view<CompoundButton>("toggle_$function").setOnCheckedChangeListener { _, checked ->
                if (!rendering) save(function, checked)
            }
        }
        updateEnabled()
        checkRoot(false)
        refreshSetup(false)
        query()
    }

    private fun refreshSetup(retry: Boolean) {
        RootAppSetup.request(this, retry) {
            if (!alive()) return@request
            val policy = getSharedPreferences("app_setup", 0)
            val idle = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            fun result(value: Boolean, yes: String = "已允许") = if (value) yes else "未确认"
            ui.render("setup" to "设置保存：${result(RootAppSetup.canSave(this), "已授权")}\n" +
                "可授予权限：${AppPermissionGrants.summary(this)}\n" +
                "悬浮窗：${result(Settings.canDrawOverlays(this))}\n" +
                "电池优化豁免：${result(idle, "已加入")}\n" +
                "ColorOS 自启动：${result(policy.getBoolean("autoStart", false))}\n" +
                "关联启动：${result(policy.getBoolean("associate", false))}\n" +
                "后台运行：${result(policy.getBoolean("background", false))}")
        }
    }

    private fun checkRoot(refresh: Boolean) {
        if (rootBusy) return
        rootBusy = true
        ui.render("root" to "正在检测…", "root_tone" to "working")
        updateEnabled()
        RootAccessCheck.check(refresh) { result ->
            if (!alive()) return@check
            rootBusy = false
            val text = when (result) {
                RootAccessCheck.Result.AUTHORIZED -> "已授权，已确认 uid 0"
                RootAccessCheck.Result.NOT_AUTHORIZED -> "未获授权"
                RootAccessCheck.Result.UNAVAILABLE -> "无法运行 su，尚未确认 Root"
                RootAccessCheck.Result.TIMED_OUT -> "检测超时，请确认授权后刷新"
                else -> "检测未完成，请刷新重试"
            }
            ui.render("root" to text, "root_tone" to if (result == RootAccessCheck.Result.AUTHORIZED) "ready" else "error")
            updateEnabled()
        }
    }

    private fun query() {
        if (busy) return
        message("正在读取设置…")
        request("diagnostics", null, saving = false)
    }

    private fun save(function: String, value: Boolean) {
        if (!connected || busy) { renderControls(); return }
        message("正在保存…")
        request("payTouch", Bundle().apply { putString("function", function); putBoolean("value", value) }, saving = true)
    }

    private fun chooseUser(app: String) {
        if (!connected || busy) return
        val state = confirmedUsers.getBundle(app) ?: return
        val users = state.getIntArray("users") ?: return
        if (users.isEmpty()) return
        val selected = state.getInt("selected", 0)
        ui.choose("${RuntimeSettingsState.appLabel(app)}启动用户", users.map(RuntimeSettingsState::userLabel).toTypedArray(), users.indexOf(selected)) { which ->
            if (!alive() || !connected || busy || users[which] == selected) return@choose
            message("正在保存启动用户…")
            request("payUser", Bundle().apply { putString("app", app); putInt("user", users[which]) }, saving = true)
        }
    }

    private fun request(operation: String, values: Bundle?, saving: Boolean) {
        busy = true
        updateEnabled()
        val generation = ++requestGeneration
        val timeout = Runnable { finishRequest(generation, null, "timeout", saving) }
        main.postDelayed(timeout, 6000)
        try {
            CardConfiguration.request(this, operation, values) { state, error -> main.post {
                if (!alive() || generation != requestGeneration || !busy) return@post
                main.removeCallbacks(timeout)
                val failure = if (saving && error == null && RuntimeSettingsState.validDiagnostics(state) &&
                    !RuntimeSettingsState.confirmedWrite(operation, requireNotNull(values), requireNotNull(state))) "unconfirmed" else error
                finishRequest(generation, state, failure, saving)
            } }
        } catch (_: RuntimeException) {
            main.removeCallbacks(timeout)
            finishRequest(generation, null, "request", saving)
        }
    }

    private fun finishRequest(generation: Long, state: Bundle?, error: String?, saving: Boolean) {
        if (!alive() || generation != requestGeneration || !busy) return
        busy = false
        if (error != null || !RuntimeSettingsState.validDiagnostics(state)) {
            connected = false
            ui.render("connection" to "卡包未连接", "framework" to "未连接", "hook" to "未连接",
                "display" to "状态未知", "framework_tone" to "error", "hook_tone" to "error", "display_tone" to "unknown")
            message(if (saving) "保存未确认，已恢复上次读取的显示。请刷新核对实际设置。"
                else "状态读取失败，请刷新重试。未连接不代表未安装框架。")
        } else {
            requireNotNull(state)
            connected = true
            confirmed = Bundle(state.getBundle("payTouchOverrides"))
            confirmedUsers = if (RuntimeSettingsState.validUsers(state.getBundle("payUsers"))) Bundle(state.getBundle("payUsers")) else Bundle()
            val displayReady = state.getBoolean("rootDisplayReady")
            ui.render("connection" to "卡包已连接", "framework" to "${state.getString("framework")} ${state.get("frameworkVersion")}",
                "hook" to "已连接并确认激活", "display" to if (displayReady) "已就绪" else "尚未就绪",
                "framework_tone" to "ready", "hook_tone" to "ready", "display_tone" to if (displayReady) "ready" else "unknown")
            message(if (saving) "已保存。启动用户在下次打开卡片时生效。" else "设置已读取。")
        }
        renderControls()
        updateEnabled()
    }

    private fun renderControls() {
        rendering = true
        try {
            for (function in RuntimeSettingsState.functions)
                ui.view<CompoundButton>("toggle_$function").isChecked = confirmed.getBoolean(function, false)
            for (app in PayUserSettings.APPS) {
                val state = confirmedUsers.getBundle(app)
                ui.render("user_${app}_value" to if (state == null) "尚未读取" else
                    RuntimeSettingsState.userLabel(state.getInt("selected", 0)) + if (state.getBoolean("available")) "" else "（未安装或不可用）")
            }
            ui.render("user_hint" to if (connected && confirmedUsers.isEmpty)
                "启动用户功能尚未加载；更新模块后需重启手机，再刷新此页。"
                else "原有微信分身专用项始终使用分身。创建分身后可刷新此页。")
        } finally { rendering = false }
    }

    private fun updateEnabled() {
        ui.view<View>("refresh").isEnabled = !busy && !rootBusy
        for (function in RuntimeSettingsState.functions) ui.view<View>("toggle_$function").isEnabled = connected && !busy
        for (app in PayUserSettings.APPS) ui.view<View>("user_$app").apply {
            isEnabled = connected && !busy && confirmedUsers.containsKey(app)
            alpha = if (isEnabled) 1f else .45f
        }
    }

    private fun message(value: String) { ui.render("message" to value) }
    override fun onDestroy() { requestGeneration++; main.removeCallbacksAndMessages(null); super.onDestroy() }
}
