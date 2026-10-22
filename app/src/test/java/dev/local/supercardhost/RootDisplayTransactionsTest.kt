package dev.local.supercardhost

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Actual Android Parcel encoding at both supported framework levels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class RootDisplayTransactionsTest {
    private val nonce = "0123456789abcdef0123456789abcdef"

    private class Endpoint(val nonce: String) : Binder() {
        val callback = Binder()
        var lifetime: IBinder? = null
        var released = -1
        var reject = 0
        var deny = false
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            checkNotNull(reply)
            assertEquals(0, flags)
            data.enforceInterface("dev.local.supercardhost.RootDisplay.v1")
            assertEquals(nonce, data.readString())
            if (deny) { reply.writeException(SecurityException("rejected")); return true }
            if (code == reject) return false
            when (code) {
                1 -> { lifetime = data.readStrongBinder(); assertNotNull(lifetime) }
                2 -> {
                    assertEquals("SuperCard_Lens", data.readString())
                    assertEquals(600, data.readInt()); assertEquals(900, data.readInt())
                    assertEquals(240, data.readInt()); assertNull(data.readTypedObject(Surface.CREATOR))
                    assertEquals(19917, data.readInt())
                }
                3 -> released = data.readInt()
                else -> error("Unexpected transaction $code")
            }
            assertEquals(0, data.dataAvail())
            reply.writeNoException()
            if (code == 2) { reply.writeInt(17); reply.writeStrongBinder(callback) }
            return true
        }
    }

    @Test fun v1PayloadAndCallbackTokenSurviveRealParcelRoundTrip() {
        val endpoint = Endpoint(nonce)
        val connection = RootDisplayConnection(Handler(Looper.getMainLooper())) {}
        assertTrue(connection.connect(endpoint, nonce))
        val session = connection.snapshot()
        assertNotNull(endpoint.lifetime)
        val created = RootDisplayTransactions.create(session, "SuperCard_Lens", 600, 900, 240, null, 19917)
        assertEquals(17, created.id); assertSame(endpoint.callback, created.callback)
        RootDisplayTransactions.release(session, created.id)
        assertEquals(17, endpoint.released)
    }

    @Test fun unsupportedOperationsAndRemoteSecurityErrorsCannotLookSuccessful() {
        val endpoint = Endpoint(nonce)
        val connection = RootDisplayConnection(Handler(Looper.getMainLooper())) {}
        endpoint.reject = 1
        try { connection.connect(endpoint, nonce); fail("Missing handshake") } catch (_: IllegalStateException) {}
        assertNull(connection.snapshot())
        endpoint.reject = 0; connection.connect(endpoint, nonce)
        val session = connection.snapshot()
        endpoint.reject = 2
        try {
            RootDisplayTransactions.create(session, "SuperCard_Lens", 600, 900, 240, null, 19917)
            fail("Missing create")
        } catch (_: IllegalStateException) {}
        endpoint.reject = 3
        try { RootDisplayTransactions.release(session, 17); fail("Missing release") } catch (_: IllegalStateException) {}
        endpoint.reject = 0; endpoint.deny = true
        try { RootDisplayTransactions.release(session, 17); fail("Remote denial") } catch (_: SecurityException) {}
        assertEquals(-1, endpoint.released)
    }

    @Test fun backgroundAnnouncementCannotRaceMainThreadDisconnectEffects() {
        val endpoint = Endpoint(nonce)
        val connection = RootDisplayConnection(Handler(Looper.getMainLooper())) {}
        val rejected = AtomicReference<Throwable>()
        Thread {
            try { connection.connect(endpoint, nonce) } catch (error: Throwable) { rejected.set(error) }
        }.apply { start(); join() }
        assertTrue(rejected.get() is IllegalStateException)
        assertNull(connection.snapshot()); assertNull(endpoint.lifetime)
    }
}
