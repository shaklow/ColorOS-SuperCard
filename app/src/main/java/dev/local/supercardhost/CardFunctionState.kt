package dev.local.supercardhost

import org.json.JSONArray
import org.json.JSONObject

/** Edits the existing function objects in place; skins, capabilities and launch info stay owned by the runtime. */
internal object CardFunctionState {
    data class Function(val type: String, val title: String, val enabled: Boolean)
    data class State(val functions: List<Function>, val minimum: Int, val maximum: Int)

    fun parse(snapshot: CardManagementState.Snapshot, cardType: String, payMaximum: Int = 6): State {
        require(cardType == "pay" || cardType == "camera") { "不支持的功能设置" }
        val functions = card(snapshot.raw, cardType).getJSONArray("functions")
        val seen = mutableSetOf<String>()
        val modes = ColorOsLensModes.query(null).associate { it.type() to it.title() }
        val values = (0 until functions.length()).mapNotNull { index ->
            val value = functions.getJSONObject(index)
            require(value.opt("type") is String) { "功能标识格式无效，未修改原配置" }
            val type = value.getString("type")
            require(type.isNotBlank() && seen.add(type)) { "功能标识无效或重复，未修改原配置" }
            for (key in listOf("enable", "support"))
                require(!value.has(key) || value.get(key) is Boolean) { "功能开关格式无效，未修改原配置" }
            if (!value.optBoolean("support", true) || type in setOf("vivo_pay", "vivo_scan") ||
                (cardType == "camera" && type !in modes)) return@mapNotNull null
            val title = if (cardType == "camera") modes.getValue(type) else when (type) {
                "wx_scan" -> "微信扫一扫"; "wx_pay" -> "微信付款码"; "wx_ride" -> "微信乘车码"
                "wx2_scan" -> "微信分身扫一扫"; "wx2_pay" -> "微信分身付款码"
                "alipay_scan" -> "支付宝扫一扫"; "alipay_pay" -> "支付宝付款码"
                "alipay_ride" -> "支付宝乘车码"; "alipay_health_code" -> "支付宝医保码"
                else -> value.optString("type_name").ifBlank { type }
            }
            Function(type, title, value.optBoolean("enable", false))
        }
        return State(values, if (cardType == "camera") 4 else 1, if (cardType == "camera") 6 else payMaximum)
    }

    fun enabled(snapshot: CardManagementState.Snapshot, cardType: String, type: String, enabled: Boolean, payMaximum: Int = 6): String {
        val state = parse(snapshot, cardType, payMaximum)
        val function = state.functions.singleOrNull { it.type == type } ?: error("功能已变化，请刷新后重试")
        if (function.enabled == enabled) return snapshot.raw
        val count = state.functions.count { it.enabled }
        require(!enabled || count < state.maximum) { "最多启用 ${state.maximum} 项功能" }
        require(enabled || count > state.minimum) { "至少保留 ${state.minimum} 项功能" }
        return edit(snapshot, cardType) { functions ->
            (0 until functions.length()).map { functions.getJSONObject(it) }.single { it.getString("type") == type }.put("enable", enabled)
        }
    }

    fun move(snapshot: CardManagementState.Snapshot, cardType: String, type: String, target: String): String {
        val visible = parse(snapshot, cardType).functions.map { it.type }
        require(type in visible && target in visible) { "功能已变化，请刷新后重试" }
        if (type == target) return snapshot.raw
        return edit(snapshot, cardType) { functions ->
            val values = (0 until functions.length()).map { functions.getJSONObject(it) }.toMutableList()
            val from = values.indexOfFirst { it.getString("type") == type }
            val to = values.indexOfFirst { it.getString("type") == target }
            values.add(to, values.removeAt(from))
            while (functions.length() > 0) functions.remove(0)
            values.forEach { functions.put(it) }
        }
    }

    private fun card(raw: String, type: String): JSONObject {
        val cards = JSONObject(raw).getJSONArray("cards")
        return (0 until cards.length()).map { cards.getJSONObject(it) }.singleOrNull { it.getString("type") == type }
            ?: error("没有找到这张卡片，请返回卡片管理后刷新")
    }
    private fun edit(snapshot: CardManagementState.Snapshot, type: String, action: (JSONArray) -> Unit): String {
        val root = JSONObject(snapshot.raw)
        val cards = root.getJSONArray("cards")
        val card = (0 until cards.length()).map { cards.getJSONObject(it) }.single { it.getString("type") == type }
        action(card.getJSONArray("functions"))
        return root.toString()
    }
}
