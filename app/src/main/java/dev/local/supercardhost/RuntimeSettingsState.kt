package dev.local.supercardhost

import android.os.Bundle

/** Validate the existing broadcast contract before enabling any setting or acknowledging a write. */
internal object RuntimeSettingsState {
    val functions = listOf("wx_scan", "alipay_scan", "wx_pay", "alipay_pay", "alipay_ride",
        "wx_ride", "wx2_scan", "wx2_pay", "alipay_health_code")

    fun validDiagnostics(state: Bundle?): Boolean {
        if (state == null || state.get("systemUiHook") != true || state.get("rootDisplayReady") !is Boolean ||
            state.get("framework") !is String || state.getString("framework").isNullOrEmpty() ||
            state.get("frameworkVersion") == null || state.get("payTouchOverrides") !is Bundle) return false
        val overrides = state.getBundle("payTouchOverrides") ?: return false
        return functions.all { !overrides.containsKey(it) || overrides.get(it) is Boolean }
    }

    fun validUsers(users: Bundle?): Boolean = users != null && PayUserSettings.APPS.all { app ->
        val value = users.getBundle(app) ?: return@all false
        val ids = value.getIntArray("users") ?: return@all false
        value.get("selected") is Int && value.get("available") is Boolean && ids.isNotEmpty() &&
            ids[0] == 0 && (1 until ids.size).all { ids[it] > ids[it - 1] }
    }

    fun confirmedWrite(operation: String, requested: Bundle, state: Bundle): Boolean = when (operation) {
        "payTouch" -> state.getBundle("payTouchOverrides")?.getBoolean(requested.getString("function"), false) == requested.getBoolean("value")
        "payUser" -> validUsers(state.getBundle("payUsers")) && state.getBundle("payUsers")
            ?.getBundle(requested.getString("app"))?.getInt("selected", -1) == requested.getInt("user")
        else -> false
    }

    fun appLabel(app: String) = if (app == PayUserSettings.WECHAT) "微信" else "支付宝"
    fun userLabel(id: Int) = if (id == 0) "主用户" else "多开用户（$id）"
}
