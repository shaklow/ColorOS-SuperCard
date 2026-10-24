package dev.local.supercardhost

import org.json.JSONObject

/** Read-only PAY projection; never accepts launch Intents or changes the saved OEM objects. */
internal object NativePayConfiguration {
    data class Choice(val type: String, val title: String, val icon: String)
    val titles = linkedMapOf(
        "wx_scan" to "微信扫一扫", "alipay_scan" to "支付宝扫一扫",
        "wx_pay" to "微信付款码", "alipay_pay" to "支付宝付款码",
        "alipay_ride" to "支付宝乘车码", "wx_ride" to "微信乘车码",
        "wx2_scan" to "微信分身扫一扫", "wx2_pay" to "微信分身付款码",
        "wx2_ride" to "微信分身乘车码", "alipay_health_code" to "支付宝医保码"
    )
    fun supported(raw: String?): Boolean = runCatching {
        if (raw.isNullOrBlank()) return@runCatching true
        val cards = JSONObject(raw).getJSONArray("cards")
        val card = (0 until cards.length()).map { cards.getJSONObject(it) }.firstOrNull { it.optString("type") == "pay" }
        val functions = card?.optJSONArray("functions") ?: return@runCatching true
        (0 until functions.length()).none { index ->
            val item = functions.getJSONObject(index)
            item.optBoolean("enable") && item.optBoolean("support", true) && item.optString("type") !in titles
        }
    }.getOrDefault(false)
    fun parse(raw: String?): List<Choice> {
        if (raw.isNullOrBlank()) return emptyList()
        val cards = JSONObject(raw).getJSONArray("cards")
        val card = (0 until cards.length()).map { cards.getJSONObject(it) }.firstOrNull { it.optString("type") == "pay" }
            ?: return emptyList()
        val functions = card.optJSONArray("functions") ?: return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until functions.length()).mapNotNull { index ->
            val item = functions.getJSONObject(index)
            val type = item.optString("type")
            if (type !in titles || !item.optBoolean("enable") || !item.optBoolean("support", true) || !seen.add(type)) null
            else Choice(type, titles.getValue(type), item.optString("iconResId"))
        }
    }
}
