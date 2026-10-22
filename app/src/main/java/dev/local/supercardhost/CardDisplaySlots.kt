package dev.local.supercardhost

/** Root display ownership: at most one PAY and one LENS display, never an arbitrary name or ID. */
internal class CardDisplaySlots<T> {
    private class Slot<T>(val id: Int, val value: T)
    private val slots = mutableMapOf<String, Slot<T>>()

    fun requireAvailable(name: String) {
        require(name == "SuperCard_Virtual" || name == "SuperCard_Lens") { "Unexpected display name" }
        check(name !in slots) { "Release this card's display before creating another" }
    }

    fun add(name: String, id: Int, value: T) {
        requireAvailable(name)
        require(id > 0 && slots.values.none { it.id == id }) { "Invalid or duplicate display ID" }
        slots[name] = Slot(id, value)
    }

    fun remove(id: Int): T? {
        if (slots.isEmpty()) return null
        val entry = slots.entries.firstOrNull { it.value.id == id }
            ?: throw IllegalArgumentException("Display ID does not match an owned card display")
        slots.remove(entry.key)
        return entry.value.value
    }

    fun drain(): List<T> = slots.values.map { it.value }.also { slots.clear() }
}
