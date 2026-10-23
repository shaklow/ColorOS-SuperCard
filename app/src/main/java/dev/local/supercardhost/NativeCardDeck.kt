package dev.local.supercardhost

/** Owns content lifetime and rejects stale results independently of windows or vendor classes. */
internal class NativeCardDeck<C : AutoCloseable>(
    private val create: (String) -> C, private val present: (C, String, Int, Int) -> Unit,
    private val clear: () -> Unit, private val failed: (Throwable) -> Unit
) : AutoCloseable {
    private var order = emptyList<String>()
    var selected: String? = null; private set
    private var content: C? = null
    var generation = 0L; private set
    private var closed = false

    fun show(cards: List<String>, requested: String? = null) {
        check(!closed)
        require(cards.isNotEmpty() && cards.distinct().size == cards.size)
        val type = requested?.takeIf { it in cards } ?: selected?.takeIf { it in cards } ?: cards.first()
        if (type == selected && cards == order && content != null) return
        val next = create(type)
        try { present(next, type, cards.indexOf(type), cards.size) }
        catch (error: Throwable) { safely(next); throw error }
        val previous = content
        generation++; content = next; selected = type; order = cards.toList()
        previous?.let(::safely)
    }
    fun move(delta: Int) {
        if (closed || content == null || order.size < 2) return
        val index = order.indexOf(selected)
        show(order, order[(index + delta).coerceIn(0, order.lastIndex)])
    }
    fun current(token: Long) = !closed && content != null && generation == token
    fun hide() {
        generation++; val previous = content; content = null; selected = null
        try { clear() } finally { previous?.let(::safely) }
    }
    override fun close() { if (!closed) { closed = true; hide() } }
    private fun safely(value: C) { try { value.close() } catch (error: Throwable) { failed(error) } }
}
