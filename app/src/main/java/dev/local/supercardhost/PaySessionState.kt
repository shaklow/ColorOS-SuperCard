package dev.local.supercardhost

/** Publishes one coherent launch identity to the task worker and asynchronous SDK callbacks. */
internal class PaySessionState {
    class Snapshot internal constructor(val manager: Any?, val function: String?, val user: Int, val generation: Long)
    @Volatile var snapshot = Snapshot(null, null, -1, 0)
        private set

    @Synchronized fun begin(manager: Any, function: String, user: Int): Snapshot {
        return Snapshot(manager, function, user, snapshot.generation + 1).also { snapshot = it }
    }

    @Synchronized fun clear(expected: Snapshot): Boolean {
        if (snapshot !== expected) return false
        snapshot = Snapshot(null, null, -1, expected.generation + 1)
        return true
    }

    fun isCurrent(expected: Snapshot): Boolean = snapshot === expected
}
