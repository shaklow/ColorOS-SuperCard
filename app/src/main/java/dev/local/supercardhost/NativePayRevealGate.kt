package dev.local.supercardhost

/** Waits for the current page's frames to settle, without inspecting or copying payment pixels. */
internal class NativePayRevealGate(private val livePreview: Boolean) {
    private var pageAt = -1L
    private var frameAt = -1L
    private var minimum = 650L
    private var eligible = false
    var revealed = false
        private set

    fun page(now: Long, recognized: Boolean, retryingEntry: Boolean) {
        pageAt = now; frameAt = -1; revealed = false
        eligible = !retryingEntry
        minimum = if (recognized) 650 else 1300
    }
    fun frame(now: Long) { if (pageAt >= 0 && !revealed) frameAt = now }
    fun remaining(now: Long): Long? {
        if (revealed || !eligible || pageAt < 0 || frameAt < pageAt) return null
        val settledAt = if (livePreview) pageAt + minimum else maxOf(pageAt + minimum, frameAt + 180)
        return (settledAt - now).coerceAtLeast(0)
    }
    fun reveal(now: Long): Boolean {
        if (remaining(now) == 0L) revealed = true
        return revealed
    }
}
