package dev.local.supercardhost

import org.json.JSONArray
import org.json.JSONObject

/** A projection of the original JSON; edits retain all unowned fields and function/skin data. */
internal object CardManagementState {
    data class Card(val type: String, val enabled: Boolean, val title: String)
    data class Snapshot(val raw: String, val cards: List<Card>)

    fun parse(raw: String): Snapshot {
        val cards = JSONObject(raw).getJSONArray("cards")
        require(cards.length() > 0) { "卡片配置为空，请刷新或重启卡包后重试" }
        val seen = mutableSetOf<String>()
        val values = (0 until cards.length()).map { index ->
            val value = cards.getJSONObject(index)
            require(value.opt("type") is String) { "卡片标识格式无效，未修改原配置" }
            val type = value.getString("type")
            require(type.isNotBlank() && seen.add(type)) { "卡片标识无效或重复，未修改原配置" }
            require(!value.has("enable") || value.get("enable") is Boolean) { "卡片开关格式无效，未修改原配置" }
            Card(type, value.optBoolean("enable", false), when (type) {
                "pay" -> "Pay 支付卡"
                "camera" -> "LENS 相机卡"
                "memory" -> "记忆卡片"
                "staging" -> "卡券"
                "dhu" -> "东华校园卡"
                else -> value.optString("typeName").ifBlank { type }
            })
        }
        return Snapshot(raw, values)
    }

    fun enabled(snapshot: Snapshot, type: String, enabled: Boolean): String {
        val card = snapshot.cards.singleOrNull { it.type == type } ?: error("卡片已变化，请刷新后重试")
        if (card.enabled == enabled) return snapshot.raw
        require(enabled || snapshot.cards.count { it.enabled } > 1) { "至少保留一张已启用的卡片" }
        val root = JSONObject(snapshot.raw)
        val cards = root.getJSONArray("cards")
        cards.getJSONObject(snapshot.cards.indexOf(card)).put("enable", enabled)
        return root.toString()
    }

    /** Move into the target's previous position; both drag/drop and arrow controls use this rule. */
    fun move(snapshot: Snapshot, type: String, target: String): String {
        val from = snapshot.cards.indexOfFirst { it.type == type }
        val to = snapshot.cards.indexOfFirst { it.type == target }
        require(from >= 0 && to >= 0) { "卡片已变化，请刷新后重试" }
        if (from == to) return snapshot.raw
        val root = JSONObject(snapshot.raw)
        val cards = root.getJSONArray("cards")
        val reordered = (0 until cards.length()).map { cards.getJSONObject(it) }.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        root.put("cards", JSONArray().apply { reordered.forEach { put(it) } })
        return root.toString()
    }
}

/** Serializes local editors and checks the original provider before and after each write. */
internal class CardManagementRepository(private val storage: Storage) {
    interface Storage {
        fun read(): String?
        fun write(raw: String): Boolean
        fun initialize()
        fun canWrite(): Boolean
    }

    fun isCurrent(snapshot: CardManagementState.Snapshot): Boolean = synchronized(lock) {
        storage.read() == snapshot.raw
    }

    fun load(): CardManagementState.Snapshot = synchronized(lock) {
        var raw = storage.read()
        if (raw.isNullOrBlank()) {
            check(storage.canWrite()) { "首次加载需要设置保存权限，请打开运行状态页完成授权" }
            storage.initialize()
            raw = storage.read()
        }
        CardManagementState.parse(checkNotNull(raw?.takeIf { it.isNotBlank() }) { "卡片配置尚未生成，请重试" })
    }

    fun change(base: CardManagementState.Snapshot, edit: (CardManagementState.Snapshot) -> String): CardManagementState.Snapshot = synchronized(lock) {
        check(storage.canWrite()) { "尚未取得设置保存权限，请打开运行状态页完成授权" }
        check(storage.read() == base.raw) { "卡片配置已在其他页面更新，请刷新后重试" }
        val updated = edit(base)
        CardManagementState.parse(updated) // Never persist an invalid result.
        if (updated != base.raw) check(storage.write(updated)) { "保存失败，原开关和顺序未确认更改" }
        val readback = storage.read()
        check(readback == updated) { "保存结果发生变化，已重新读取卡片配置" }
        CardManagementState.parse(updated)
    }

    private companion object { val lock = Any() }
}
