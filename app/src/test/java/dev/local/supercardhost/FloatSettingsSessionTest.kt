package dev.local.supercardhost

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class FloatSettingsSessionTest {
    private class Clock : FloatSettingsSession.Scheduler {
        var time = 1000L
        private val tasks = linkedMapOf<Runnable, Long>()
        override fun now() = time
        override fun epoch() = time * 1_000_000
        override fun execute(action: Runnable) = after(action, 0)
        override fun after(action: Runnable, delay: Long) { tasks[action] = time + delay }
        override fun cancel(action: Runnable) { tasks.remove(action) }
        fun advance(delay: Long = 0) {
            val end = time + delay
            while (true) {
                val next = tasks.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
                time = next.value; tasks.remove(next.key); next.key.run()
            }
            time = end
        }
        fun count() = tasks.size
    }
    private data class Request(val operation: String, val values: Bundle?, val callback: FloatSettingsSession.Result)
    private class Fixture : FloatSettingsSession.Listener {
        val clock = Clock(); val sent = mutableListOf<Request>(); val waiting = mutableListOf<Request>()
        var renders = 0; val errors = mutableListOf<String>()
        val session = FloatSettingsSession({ operation, values, callback ->
            Request(operation, values?.let(::Bundle), callback).also { sent.add(it); waiting.add(it) }
        }, clock, this)
        override fun changed() { renders++ }
        override fun failed(message: String, error: String?) { errors.add(message) }
        fun take(operation: String): Request = waiting.first { it.operation == operation }.also { waiting.remove(it) }
        fun complete(request: Request, value: Bundle? = snapshot(), error: String? = null) {
            request.callback.complete(value, error); clock.advance()
        }
        fun load() { session.resume(); complete(take("query")) }
    }
    companion object {
        private fun snapshot(top: Float = .76f, bottom: Float = .98f, opacity: Float = .4f) = Bundle().apply {
            putFloat("top", top); putFloat("bottom", bottom); putFloat("handleOpacity", opacity)
            putBoolean("handleVisible", true); putBoolean("handleAutoHide", true)
            putBoolean("enabled", true); putBoolean("unlocked", true); putInt("lockStyle", 2)
            putString("future", "preserve")
        }
    }
    @Test fun latestEditsAreCoalescedAndFinishAfterCloseWithoutUiOrTimers() {
        val f = Fixture(); f.load()
        f.session.range(0, 200); f.session.range(0, 600); f.session.opacity(.71f)
        f.clock.advance(600)
        val first = f.take("range")
        assertEquals(.6f * (1 - .22f), first.values!!.getFloat("top"), .0001f)
        f.session.range(.1f, .4f); val renders = f.renders
        f.session.close()
        f.complete(first, snapshot(first.values.getFloat("top"), first.values.getFloat("bottom")))
        f.complete(f.take("handleOpacity"), snapshot(opacity = .71f))
        val last = f.take("range"); assertEquals(.1f, last.values!!.getFloat("top"), 0f)
        f.complete(last, snapshot(.1f, .4f, .71f))
        assertEquals(listOf("range", "handleOpacity", "range"), f.sent.filter { it.operation in listOf("range", "handleOpacity") }.map { it.operation })
        assertEquals(renders, f.renders); assertTrue(f.errors.isEmpty()); assertEquals(0, f.clock.count())
        assertEquals(.1f, f.session.state().top, 0f)
    }
    @Test fun pausedQueryAndPreviewResultsCannotAffectResumedSession() {
        val f = Fixture(); f.session.resume(); val oldQuery = f.take("query")
        f.session.pause(); f.clock.advance(1); f.session.resume(); val current = f.take("query")
        f.complete(oldQuery, snapshot(.1f, .5f)); assertFalse(f.session.loaded())
        f.complete(current); val oldPreview = f.take("preview")
        f.session.pause(); f.clock.advance(1); f.session.resume(); f.complete(f.take("query"))
        f.clock.advance(50); val nextPreview = f.take("preview"); val before = f.renders
        f.complete(oldPreview, null, "old disconnected")
        assertEquals(before, f.renders); assertTrue(f.errors.isEmpty())
        assertTrue(nextPreview.values!!.getLong("previewEpoch") > oldPreview.values!!.getLong("previewEpoch"))
        f.complete(nextPreview); f.session.close()
        assertEquals(3, f.sent.count { it.operation == "previewEnd" })
    }
    @Test fun acknowledgementDoesNotOverwriteNewerThumbPositionOrDuplicateRequests() {
        val f = Fixture(); f.load(); f.session.range(.1f, .5f); f.session.stopTracking()
        val old = f.take("range"); f.session.startTracking(1); f.session.range(.2f, .6f)
        f.complete(old, snapshot(.1f, .5f)); assertEquals(.2f, f.session.state().top, 0f)
        val current = f.take("range"); f.session.stopTracking(); f.complete(current, snapshot(.2f, .6f))
        f.complete(current, null, "duplicate failure")
        assertTrue(f.errors.isEmpty()); assertEquals(.2f, f.session.state().top, 0f)
        f.session.close()
    }
    @Test fun saveFailureRestoresConfirmedValuesAndClosedFailuresStaySilent() {
        val f = Fixture(); f.load(); f.session.visible(false)
        f.complete(f.take("handleVisible"), null, "not saved")
        assertTrue(f.session.state().visible); assertEquals(1, f.errors.size)
        f.clock.advance(1000); val retry = f.take("query")
        f.session.close(); val renders = f.renders
        f.complete(retry, null, "late read failure")
        f.complete(f.take("preview"), null, "late preview failure")
        assertEquals(renders, f.renders); assertEquals(1, f.errors.size); assertEquals(0, f.clock.count())
    }
    @Test fun shortDisconnectsRetryWithoutRepeatedWarnings() {
        val f = Fixture(); f.session.resume()
        for (i in 0..6) {
            f.complete(f.take("query"), null, "offline")
            if (i < 5) assertTrue(f.errors.isEmpty())
            f.clock.advance(1000)
        }
        assertEquals(1, f.errors.size)
        f.complete(f.take("query")); assertTrue(f.session.loaded()); f.session.close()
    }
    @Test fun resetChangesOnlyAppearanceAndKeepsTriggerChoicesAndMetadata() {
        val state = FloatSettingsState.read(snapshot(.2f, .8f).apply {
            putBoolean("unlocked", false); putBoolean("handleAutoHide", false); putBoolean("handleVisible", false)
        })!!.reset().snapshot()
        assertEquals(.76f, state.getFloat("top"), 0f); assertEquals(.4f, state.getFloat("handleOpacity"), 0f)
        assertFalse(state.getBoolean("unlocked")); assertFalse(state.getBoolean("handleAutoHide")); assertFalse(state.getBoolean("handleVisible"))
        assertEquals(2, state.getInt("lockStyle")); assertEquals("preserve", state.getString("future"))
        val pixelRounded = FloatSettingsState.read(snapshot(.96f, .9995f))!!
        assertTrue(pixelRounded.preview().getFloat("bottom") - pixelRounded.preview().getFloat("top") >= .04f)
        assertNull(FloatSettingsState.read(snapshot(Float.NaN, 1f)))
    }
}
