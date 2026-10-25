package dev.local.supercardhost

import android.app.KeyguardManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class WechatCodeChannelTest {
    private class Fixture : WechatCodeChannel.Listener {
        val app = RuntimeEnvironment.getApplication()
        val channel = WechatCodeChannel(app, "wx_pay", 0, this)
        val values = mutableListOf<WechatCodeChannel.Code>()
        var connections = 0; var clears = 0; var authorizations = 0; var stops = 0
        private fun field(name: String) = channel.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val nonce = field("nonce").get(channel) as String
        val peer = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (!WechatCodeWire.check(data, nonce, 9)) return false
                if (code == WechatCodeWire.AUTHORIZE) { assertEquals("b".repeat(64), data.readString()); authorizations++ }
                else if (code == WechatCodeWire.STOP) stops++ else return false
                reply?.writeNoException(); reply?.writeInt(1); return true
            }
        }
        init {
            // Exercise the production Binder parser without pretending Robolectric can resolve a ROM's cross-user PackageManager.
            field("display").setInt(channel, 9); field("uid").setInt(channel, 10123)
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(false); lock.setIsDeviceLocked(false)
        }
        override fun connected() { connections++ }
        override fun code(value: WechatCodeChannel.Code) { values.add(value) }
        override fun cleared() { clears++ }
        fun call(kind: Int, uid: Int = 10123, token: String = nonce, display: Int = 9,
            sequence: Long = 1, timestamp: Long = SystemClock.elapsedRealtime(), task: Int = 42,
            page: String = "com.tencent.mm.framework.app.UIPageFragmentActivity", value: String = "100000000000000001", extra: Boolean = false): Boolean {
            ShadowBinder.setCallingUid(uid)
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                WechatCodeWire.header(data, token, display)
                if (kind == WechatCodeWire.HELLO) { data.writeString("b".repeat(64)); data.writeStrongBinder(peer) }
                else { data.writeLong(sequence); data.writeLong(timestamp); data.writeInt(task); data.writeString(page); if (kind == WechatCodeWire.CODE) data.writeString(value) }
                if (extra) data.writeString("x".repeat(2048))
                assertTrue(channel.transact(kind, data, reply, 0)); reply.readException(); return reply.readInt() == 1
            } finally { data.recycle(); reply.recycle(); ShadowBinder.setCallingUid(Process.myUid()) }
        }
        fun idle() { shadowOf(android.os.Looper.getMainLooper()).idle() }
    }
    @Test fun senderUidNonceDisplayHandshakeAndPageAreAllRequired() {
        val f = Fixture()
        try {
            assertFalse(f.call(WechatCodeWire.CODE))
            assertFalse(f.call(WechatCodeWire.HELLO, uid = 10124))
            assertFalse(f.call(WechatCodeWire.HELLO, token = "c".repeat(64)))
            assertFalse(f.call(WechatCodeWire.HELLO, display = 0))
            assertEquals(0, f.authorizations)
            assertTrue(f.call(WechatCodeWire.HELLO)); f.idle()
            assertEquals(1, f.authorizations); assertEquals(1, f.connections)
            assertFalse(f.call(WechatCodeWire.HELLO))
            assertFalse(f.call(WechatCodeWire.CODE, task = -1))
            assertFalse(f.call(WechatCodeWire.CODE, page = "com.tencent.mm.ui.LauncherUI"))
            assertFalse(f.call(WechatCodeWire.CODE, value = "not-a-payment-code"))
            assertFalse(f.call(WechatCodeWire.CODE, extra = true))
            assertTrue(f.call(WechatCodeWire.CODE)); f.idle(); assertEquals(1, f.values.size)
            assertTrue(f.channel.current(f.values.single()))
            assertFalse(f.call(WechatCodeWire.CODE))
            assertTrue(f.call(WechatCodeWire.CLEAR, sequence = 2)); f.idle()
            assertEquals(1, f.clears); assertFalse(f.channel.current(f.values.single()))
        } finally { f.channel.close() }
        assertEquals(1, f.stops)
        assertFalse(f.call(WechatCodeWire.CODE, sequence = 3))
    }
    @Test fun queuedCodeIsCancelledByCloseLockOrPeerDeath() {
        for (reason in listOf("close", "lock", "death")) {
            val f = Fixture()
            try {
                assertTrue(f.call(WechatCodeWire.HELLO)); f.idle()
                assertTrue(f.call(WechatCodeWire.CODE))
                when (reason) {
                    "close" -> f.channel.close()
                    "lock" -> shadowOf(f.app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
                    "death" -> shadowOf(f.peer).deathRecipients.single().binderDied()
                }
                f.idle()
                // Death may follow an already queued code, but must revoke it before a renderer can keep it.
                if (reason == "death") { assertFalse(f.channel.connected); assertTrue(f.values.all { !f.channel.current(it) }) }
                else assertTrue(f.values.isEmpty())
            } finally { f.channel.close() }
        }
    }
}
