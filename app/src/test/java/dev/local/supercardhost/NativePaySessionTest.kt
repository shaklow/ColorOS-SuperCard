package dev.local.supercardhost

import android.content.ComponentName
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
class NativePaySessionTest {
    private class Backend : NativePaySession.Backend {
        var id = 9; var releases = 0; var failedLaunch = false
        val state = PaySessionState()
        val launches = mutableListOf<Triple<String, Int, Int>>()
        val promoted = mutableListOf<Triple<Int, Int, Int>>()
        val actions = mutableListOf<Int>()
        var lastX = 0f; var lastY = 0f
        override fun create(surface: Surface, size: NativePayGeometry.Size) = object : LensCameraSession.Display {
            override val id get() = this@Backend.id
            override fun close() { releases++ }
        }
        override fun bind(owner: NativePayDisplayOwner, function: String, user: Int) { state.begin(owner, function, user) }
        override fun unbind(owner: NativePayDisplayOwner) { val old = state.snapshot; if (old.manager === owner) state.clear(old) }
        override fun launch(function: String, user: Int, display: Int) {
            launches.add(Triple(function, user, display)); if (failedLaunch) throw SecurityException("Rejected")
        }
        override fun retry(function: String, user: Int, display: Int) { launches.add(Triple(function, user, display)) }
        override fun inject(event: MotionEvent, display: Int): Boolean {
            assertEquals(id, display); actions.add(event.actionMasked); lastX = event.x; lastY = event.y; return true
        }
        override fun promote(task: Int, display: Int, user: Int) { promoted.add(Triple(task, display, user)) }
    }
    private class Events : NativePaySession.Events {
        var changes = 0; var closes = 0; val full = mutableListOf<Long>()
        override fun changed() { changes++ }
        override fun fullscreen(delay: Long) { full.add(delay) }
        override fun closed() { closes++ }
    }
    private fun start(backend: Backend, events: Events = Events(), function: String = "wx_pay", user: Int = 991): NativePaySession {
        val buffer = SurfaceTexture(0); val surface = Surface(buffer)
        try { return NativePaySession(backend, function, user, events).also { it.start(surface, NativePayGeometry.Size(600, 900, 320)) } }
        finally { surface.release(); buffer.release() }
    }
    private fun page(name: String) = ComponentName("com.tencent.mm", name)
    private val wallet = page("com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI")

    @Test fun nativeCodeMustMatchTheLiveTaskAndCannotSurviveAuthenticationOrClose() {
        val backend = Backend(); val session = start(backend)
        fun code(task: Int = 42, component: ComponentName = wallet) = WechatCodeChannel.Code("100000000000000001", component, task, 1, 0)
        assertFalse(session.matchesCode(code()))
        session.resumed(wallet, 42); assertTrue(session.matchesCode(code()))
        assertFalse(session.matchesCode(code(43)))
        assertFalse(session.matchesCode(code(component = page("com.tencent.mm.framework.app.UIPageFragmentActivity"))))
        backend.id = -1; assertFalse(session.matchesCode(code())); backend.id = 9
        session.resumed(page("com.tencent.mm.plugin.walletlock.fingerprint.ui.FingerprintWalletLockUI"), 42)
        assertFalse(session.matchesCode(code()))
        session.resumed(wallet, 42); assertFalse(session.matchesCode(code()))
        session.close(); assertFalse(session.matchesCode(code()))
        val scan = start(Backend(), function = "wx_scan"); scan.resumed(wallet, 42)
        assertFalse(scan.matchesCode(code())); scan.close()
    }

    @Test fun launchesCapturedAccountAndInvalidatesOnlyItsOwnSession() {
        val backend = Backend(); val session = start(backend)
        assertSame(session, backend.state.snapshot.manager)
        assertEquals(listOf(Triple("wx_pay", 991, 9)), backend.launches)
        val replacement = Any(); backend.state.begin(replacement, "alipay_pay", 0)
        session.close(); session.close()
        assertSame(replacement, backend.state.snapshot.manager)
        assertEquals(1, backend.releases)
        assertEquals(-1, session.displayId)
    }
    @Test fun rejectedLaunchAndMainDisplayAreReleasedWithoutFullscreenFallback() {
        for (main in listOf(false, true)) {
            val backend = Backend().apply { id = if (main) 0 else 9; failedLaunch = !main }
            try { start(backend); fail("Launch must fail") } catch (_: IllegalStateException) {} catch (_: SecurityException) {}
            assertEquals(1, backend.releases)
            assertNull(backend.state.snapshot.manager)
            assertFalse(backend.launches.any { it.third == 0 })
        }
    }
    @Test fun optInCoordinatesAndAuthenticationCancelStayOnOwnedDisplay() {
        val backend = Backend(); val events = Events(); val session = start(backend, events)
        val down = MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 25f, 40f, 0)
        try {
            assertFalse(session.touch(down, 300, 450, true))
            session.resumed(wallet, 42)
            assertFalse(session.interactive(false))
            assertFalse(session.touch(down, 300, 450, false))
            assertTrue(session.touch(down, 300, 450, true))
            assertEquals(50f, backend.lastX, .01f); assertEquals(80f, backend.lastY, .01f)
            assertEquals(25f, down.x, .01f); assertEquals(40f, down.y, .01f)
            session.resumed(page("com.tencent.mm.plugin.walletlock.fingerprint.ui.FingerprintWalletLockUI"), 42)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), backend.actions)
            assertFalse(session.interactive(true)); assertEquals(listOf(300L), events.full)
            session.fullscreen()
            assertEquals(listOf(Triple(42, 9, 991)), backend.promoted)
            assertEquals(1, backend.launches.size)
            assertEquals(1, backend.releases)
        } finally { down.recycle(); session.close() }
    }
    @Test fun originalMiniProgramTouchIsKeptButForeignPagesCannotEnableIt() {
        val backend = Backend(); val session = start(backend, function = "wx_ride")
        session.resumed(ComponentName("other.app", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"), 10)
        assertFalse(session.hasPage); assertFalse(session.interactive(true))
        session.resumed(page("com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"), 10)
        assertTrue(session.interactive(false)); session.close()
    }
    @Test fun newerWeChatContainerRemainsVisibleWithDefaultClickProtection() {
        val backend = Backend(); val events = Events(); val session = start(backend, events)
        session.resumed(page("com.tencent.mm.framework.app.UIPageFragmentActivity"), 10)
        assertTrue(session.reachedPage); assertFalse(session.interactive(true))
        assertTrue(events.full.isEmpty()); session.close()
    }
    @Test fun aRemovedDisplayNeverAcceptsFurtherInputOrTaskCallbacks() {
        val backend = Backend(); val events = Events(); val session = start(backend, events)
        session.resumed(wallet, 10); val changes = events.changes
        backend.id = -1
        assertFalse(session.interactive(true))
        session.resumed(wallet, 11); assertEquals(changes, events.changes)
        session.close(); assertEquals(1, backend.releases)
    }
    @Test fun scanResultHandoffRequiresAnActualScannerAndKeepsOriginalDelay() {
        val backend = Backend(); val events = Events(); val session = start(backend, events, "wx_scan")
        session.resumed(page("com.tencent.mm.ui.LauncherUI"), 11)
        assertTrue(events.full.isEmpty())
        session.resumed(page("com.tencent.mm.plugin.scanner.ui.BaseScanUI"), 11)
        session.resumed(page("com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"), 11)
        session.resumed(page("com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"), 11)
        assertEquals(listOf(1000L), events.full)
        session.close()
    }
    @Test fun closeCancelsOnlyAnUnfinishedGesture() {
        for (finished in listOf(false, true)) {
            val backend = Backend(); val session = start(backend); session.resumed(wallet, 1)
            for (action in if (finished) listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP) else listOf(MotionEvent.ACTION_DOWN)) {
                val event = MotionEvent.obtain(1, 2, action, 20f, 30f, 0)
                try { assertTrue(session.touch(event, 600, 900, true)) } finally { event.recycle() }
            }
            session.close()
            assertEquals(listOf(MotionEvent.ACTION_DOWN, if (finished) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL), backend.actions)
            assertEquals(1, backend.releases)
        }
    }
    @Test fun alipaySchemeRetryIsBoundedAndKeepsTheCapturedAccountAndDisplay() {
        val backend = Backend(); val session = start(backend, function = "alipay_pay", user = 998)
        session.resumed(ComponentName("com.eg.android.AlipayGphone", "com.alipay.mobile.quinox.SchemeLauncherActivity"), 19)
        assertTrue(session.needsRetry); assertFalse(session.reachedPage)
        session.retry(); session.retry()
        assertEquals(listOf(Triple("alipay_pay", 998, 9), Triple("alipay_pay", 998, 9)), backend.launches)
        session.close(); session.retry(); assertEquals(2, backend.launches.size)
    }
}
