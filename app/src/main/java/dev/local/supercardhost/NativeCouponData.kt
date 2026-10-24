package dev.local.supercardhost

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Value-only projection of authenticated wallet data; no plugin beans or screenshot recognition. */
internal object NativeCouponData {
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
    }
    data class Item(val key: String, val title: String, val category: String, val merchant: String,
        val time: String, val status: String, val usable: Boolean, val qr: String,
        val image: String, val imageUrl: String, val source: String, val originalImage: String,
        val douyin: Boolean, val pending: Boolean) {
        val hint get() = when {
            !usable -> status
            douyin -> "抖音未提供券码，需在抖音内使用"
            image.isNotEmpty() || imageUrl.isNotEmpty() || qr.isNotEmpty() -> "二维码来自钱包，请向商家出示"
            pending -> "券码正在补全，可打开钱包查看"
            else -> "此券暂未提供二维码，可打开钱包查看"
        }
        fun hint(state: String): String = if (usable && !douyin && pending && qr.isEmpty() && image.isEmpty() && imageUrl.isEmpty()
            && state != "updating_codes") "券码暂未读取成功，可打开钱包查看" else hint
    }
    fun parse(data: JSONObject, hidden: (String) -> Boolean): List<Item> {
        val result = linkedMapOf<String, Item>()
        for ((array, scene, id) in listOf(Triple("films", "film", "card_id"),
            Triple("performances", "performance", "id"), Triple("coupons", "coupon", "entityId"))) {
            val rows = data.optJSONArray(array) ?: continue
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val identifier = row.optString(id).takeIf { it.isNotBlank() } ?: continue
                val key = "${scene}___$identifier"
                val status = row.optInt("orderStatus")
                if (status !in 1..4 || key in result || hidden(key)) continue
                val category = when (scene) { "film" -> "电影票"; "performance" -> "演出活动"; else -> "消费券" }
                val titleKey = when (scene) { "film" -> "movieTitle"; "performance" -> "projectName"; else -> "couponName" }
                val merchantKey = when (scene) { "film" -> "cinemaName"; "performance" -> "venue"; else -> "merchantName" }
                val from = row.optLong(when (scene) { "film" -> "startTime"; "performance" -> "startShowTime"; else -> "useValidStartTime" })
                val to = row.optLong(when (scene) { "film" -> "endTime"; "performance" -> "endShowTime"; else -> "useValidEndTime" })
                val label = when (status) {
                    1 -> "待使用"; 2 -> "已使用"; 3 -> if (scene == "film") "已结束" else "已过期"
                    else -> when (scene) { "film" -> "已放映"; "performance" -> "已结束"; else -> "已过期" }
                }
                result[key] = Item(key, row.optString(titleKey).ifBlank { category }, category, row.optString(merchantKey),
                    range(scene, from, to), label, status == 1, row.optString("qrCode"), row.optString("_qrImageBase64"),
                    row.optString("_qrImageUrl"), row.optString("_openIntent", row.optString("_sourceIntent")),
                    row.optString("_originalImageUri"), row.optString("_codeMode") == "douyin", row.optBoolean("_codePending"))
            }
        }
        return result.values.toList()
    }
    private fun range(scene: String, from: Long, to: Long): String {
        val format = checkNotNull(dateFormat.get())
        fun text(value: Long) = if (value > 0) format.format(Date(value)) else ""
        val start = text(from); val end = text(to)
        if (start.isEmpty()) return if (end.isNotEmpty() && scene != "film") "$end 到期" else end
        if (end.isEmpty()) return start
        return "$start - ${if (scene == "film" || to - from <= 86_400_000) end.substringAfter(' ') else end}"
    }
}

/** Selection survives same-account code updates, but never an invalidated wallet snapshot. */
internal class NativeCouponSession(private val store: Store) {
    interface Store {
        fun snapshot(): JSONObject?
        fun current(account: String): Boolean
        fun hidden(key: String): Boolean
        fun remove(key: String): Boolean
    }
    var items = emptyList<NativeCouponData.Item>(); private set
    var selected: String? = null; private set
    var account: String? = null; private set
    var active = false; private set
    private var closed = false
    fun refresh() {
        if (closed) return
        val data = store.snapshot()
        val next = data?.optString("_walletAccountKey")?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) && store.current(it) }
        val rows = if (next != null) NativeCouponData.parse(checkNotNull(data), store::hidden) else emptyList()
        if (next != account || rows.none { it.key == selected }) selected = null
        account = next; items = rows
    }
    fun current(key: String? = selected): NativeCouponData.Item? = if (closed || !active || account?.let(store::current) != true)
        null else items.firstOrNull { it.key == key }
    fun select(key: String): Boolean {
        if (current(key) == null) return false
        selected = key; return true
    }
    fun back() { selected = null }
    fun invalidate() { items = emptyList(); selected = null; account = null }
    fun setActive(value: Boolean) { if (!closed) active = value }
    fun removeSelected(): Boolean {
        val row = current() ?: return false
        if (!store.remove(row.key)) return false
        refresh(); return true
    }
    fun close() { closed = true; active = false; invalidate() }
}
