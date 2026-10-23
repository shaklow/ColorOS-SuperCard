package dev.local.supercardhost

/** Main-thread startup transaction: only preparation may be retried after failure. */
internal class RuntimeBootstrapSession(private val scheduler: Scheduler, private val driver: Driver) : AutoCloseable {
    interface Scheduler {
        fun post(action: Runnable, delay: Long): Boolean
        fun cancel(action: Runnable)
    }
    interface Driver {
        fun prepare(): Runnable
        fun changed() {}
        fun failed(phase: String, error: Throwable) {}
    }
    enum class Phase { IDLE, WAITING, PREPARING, ATTACHING, RUNNING, FAILED, CLOSED }
    var phase = Phase.IDLE; private set
    var attempts = 0; private set
    var requiresRestart = false; private set
    var lastError: String? = null; private set
    private var generation = 0L
    private var pending: Runnable? = null
    private val delays = longArrayOf(1_000, 3_000, 10_000, 30_000)

    fun request() {
        if (phase != Phase.IDLE && (phase != Phase.FAILED || requiresRestart)) return
        attempts = 0; lastError = null; enqueue(0)
    }
    private fun enqueue(delay: Long) {
        phase = Phase.WAITING
        val ticket = ++generation
        val action = Runnable { if (ticket == generation && phase == Phase.WAITING) runAttempt(ticket) }
        pending = action
        if (!scheduler.post(action, delay)) {
            pending = null; phase = Phase.FAILED; lastError = "Main thread rejected startup"
        }
        changed()
    }
    private fun runAttempt(ticket: Long) {
        pending = null; attempts++; phase = Phase.PREPARING; changed()
        if (ticket != generation || phase == Phase.CLOSED) return
        val attach = try { driver.prepare() } catch (error: Throwable) {
            if (ticket != generation || phase == Phase.CLOSED) return
            lastError = error.javaClass.simpleName
            failed("prepare", error)
            if (ticket != generation || phase == Phase.CLOSED) return
            val delay = delays.getOrNull(attempts - 1)
            if (delay == null) { phase = Phase.FAILED; changed() } else enqueue(delay)
            return
        }
        if (ticket != generation || phase == Phase.CLOSED) return
        // Hooks and the original service have process-wide effects. Never repeat this boundary.
        requiresRestart = true; phase = Phase.ATTACHING; changed()
        if (ticket != generation || phase == Phase.CLOSED) return
        try {
            attach.run()
            if (phase != Phase.CLOSED) { requiresRestart = false; phase = Phase.RUNNING; lastError = null; changed() }
        } catch (error: Throwable) {
            if (phase != Phase.CLOSED) {
                lastError = error.javaClass.simpleName; phase = Phase.FAILED
                failed("attach", error); changed()
            }
        }
    }
    private fun changed() { try { driver.changed() } catch (_: Throwable) { } }
    private fun failed(phase: String, error: Throwable) { try { driver.failed(phase, error) } catch (_: Throwable) { } }
    override fun close() {
        if (phase == Phase.CLOSED) return
        generation++; pending?.let(scheduler::cancel); pending = null; phase = Phase.CLOSED; changed()
    }
}
