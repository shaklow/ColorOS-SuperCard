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
        // Task observation polls every 300ms and may arrive after a static page's last buffer.
        // Only a recent buffer from this newly owned display may bridge that notification delay.
        if (frameAt < now - 400) frameAt = -1
        pageAt = now; revealed = false
        eligible = !retryingEntry
        minimum = if (recognized) 650 else 1300
    }
    fun frame(now: Long) { if (!revealed) frameAt = now }
    fun remaining(now: Long): Long? {
        if (revealed || !eligible || pageAt < 0 || frameAt < 0) return null
        val settledAt = if (livePreview) pageAt + minimum else maxOf(pageAt + minimum, frameAt + 180)
        return (settledAt - now).coerceAtLeast(0)
    }
    fun reveal(now: Long): Boolean {
        if (remaining(now) == 0L) revealed = true
        return revealed
    }
}
