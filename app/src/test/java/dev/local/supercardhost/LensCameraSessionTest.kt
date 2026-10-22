package dev.local.supercardhost

import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class LensCameraSessionTest {
    private class Backend : LensCameraSession.Backend {
        var id = 7; var launches = 0; var releases = 0; var failLaunch = false
        val events = mutableListOf<MotionEvent>()
        override fun create(surface: Surface, width: Int, height: Int, density: Int) = object : LensCameraSession.Display {
            override val id get() = this@Backend.id
            override fun close() { releases++ }
        }
        override fun launch(intent: Intent, display: Int) {
            assertEquals(id, display); launches++
            if (failLaunch) throw SecurityException("secondary launch rejected")
        }
        override fun inject(event: MotionEvent, display: Int): Boolean {
            assertEquals(id, display); events.add(MotionEvent.obtain(event)); return true
        }
    }
    private fun start(backend: Backend): LensCameraSession {
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try { return LensCameraSession(backend).also { it.start(surface, 600, 900, 320, Intent("camera")) } }
        finally { surface.release(); texture.release() }
    }

    @Test fun coordinatesAndCancellationStayOnTheOwnedDisplay() {
        val backend = Backend(); val session = start(backend)
        val down = MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 25f, 40f, 0)
        try {
            assertTrue(session.touch(down, 300, 450))
            assertEquals(25f, down.x, .001f); assertEquals(40f, down.y, .001f)
            assertEquals(50f, backend.events[0].x, .001f); assertEquals(80f, backend.events[0].y, .001f)
            session.close(); session.close()
            assertEquals(1, backend.releases)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), backend.events.map { it.actionMasked })
            assertFalse(session.touch(down, 300, 450))
        } finally { down.recycle(); backend.events.forEach { it.recycle() }; session.close() }
    }

    @Test fun completedGestureDoesNotGenerateAnotherCancel() {
        val backend = Backend(); val session = start(backend)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(1, 2, action, 20f, 30f, 0)
            try { assertTrue(session.touch(event, 600, 900)) } finally { event.recycle() }
        }
        session.close(); assertEquals(2, backend.events.size); assertEquals(1, backend.releases)
        backend.events.forEach { it.recycle() }
    }

    @Test fun rejectedLaunchReleasesTheDisplayWithoutLaunchingElsewhere() {
        val backend = Backend().apply { failLaunch = true }
        try { start(backend); fail("launch must fail") } catch (_: SecurityException) {}
        assertEquals(1, backend.launches); assertEquals(1, backend.releases)
    }

    @Test fun mainDisplayIsRejectedAndReleased() {
        val backend = Backend().apply { id = 0 }
        try { start(backend); fail("main display is forbidden") } catch (_: IllegalStateException) {}
        assertEquals(0, backend.launches); assertEquals(1, backend.releases)
    }
}
