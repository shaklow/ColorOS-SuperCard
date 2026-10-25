package dev.local.supercardhost

import android.content.ComponentName
import android.os.Binder
import android.os.Parcel
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class WechatCodeTest {
    private val nonce = "a".repeat(64)
    private val challenge = "b".repeat(64)
    @Test fun onlyPaymentFunctionsAndObservedCredentialFormatAreAccepted() {
        assertTrue(WechatCodeWire.payment("100000000000000000"))
        assertTrue(WechatCodeWire.payment("159999999999999999"))
        for (value in listOf(null, "", "169999999999999999", "10000000000000000", "1000000000000000000", "https://example.test/", "10000000000000000x")) assertFalse(WechatCodeWire.payment(value))
        assertTrue(WechatCodeWire.function("wx_pay")); assertTrue(WechatCodeWire.function("wx2_pay"))
        for (function in listOf("wx_scan", "wx_ride", "alipay_pay")) assertFalse(WechatCodeWire.function(function))
        assertFalse(WechatCodeWire.page(ComponentName("other.app", "com.tencent.mm.framework.app.UIPageFragmentActivity")))
        assertFalse(WechatCodeWire.page(ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")))
        val tokens = (1..8).map { WechatCodeWire.nonce() }
        assertTrue(tokens.all(WechatCodeWire::token)); assertEquals(8, tokens.toSet().size)
    }
    @Test fun staleFutureReplayedAndClosedMessagesCannotAdvanceTheSession() {
        val order = WechatCodeOrder()
        assertFalse(order.accept(0, 20000, 20000))
        assertFalse(order.accept(1, 9999, 20000))
        assertFalse(order.accept(1, 20001, 20000))
        assertFalse(order.accept(1, -1, 20000))
        assertTrue(order.accept(1, 10000, 20000))
        assertFalse(order.accept(1, 20000, 20000))
        assertTrue(order.accept(3, 20000, 20000))
        assertFalse(order.accept(2, 20000, 20000))
        order.close(); assertFalse(order.accept(4, 20000, 20000)); assertEquals(3L, order.sequence)
    }
    private fun call(control: Binder, uid: Int, kind: Int = WechatCodeWire.AUTHORIZE,
        token: String = nonce, display: Int = 9, proof: String = challenge, extra: Boolean = false): Boolean {
        ShadowBinder.setCallingUid(uid)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            WechatCodeWire.header(data, token, display)
            if (kind == WechatCodeWire.AUTHORIZE) data.writeString(proof)
            if (extra) data.writeInt(42)
            assertTrue(control.transact(kind, data, reply, 0)); reply.readException()
            return reply.readInt() == 1
        } finally { data.recycle(); reply.recycle(); ShadowBinder.setCallingUid(Process.myUid()) }
    }
    @Test fun reverseChallengeRequiresSystemUidAndExactSessionThenCloseIsFinal() {
        var stopped = 0
        val control = WechatCodeControl(nonce, 9, challenge) { stopped++ }
        assertFalse(control.authorized)
        assertFalse(call(control, 10123)); assertFalse(control.authorized)
        assertFalse(call(control, 1000, token = "c".repeat(64)))
        assertFalse(call(control, 1000, display = 0))
        assertFalse(call(control, 1000, proof = nonce))
        assertFalse(call(control, 1000, extra = true)); assertFalse(control.authorized)
        assertTrue(call(control, 1000)); assertTrue(control.authorized)
        assertFalse(call(control, 10123, WechatCodeWire.STOP)); assertTrue(control.authorized)
        assertTrue(call(control, 1000, WechatCodeWire.STOP)); assertFalse(control.authorized); assertEquals(1, stopped)
        assertFalse(call(control, 1000)); assertFalse(control.authorized)
        assertFalse(call(control, 1000, WechatCodeWire.STOP)); assertEquals(1, stopped)
    }
    @Test fun oversizedAndWrongInterfacePacketsAreRejected() {
        val data = Parcel.obtain()
        try {
            WechatCodeWire.header(data, nonce, 9); data.writeString("x".repeat(2048)); data.setDataPosition(0)
            assertFalse(WechatCodeWire.check(data, nonce, 9))
            data.setDataSize(0); data.setDataPosition(0); data.writeInterfaceToken("other"); data.writeString(nonce); data.writeInt(9); data.setDataPosition(0)
            try { WechatCodeWire.check(data, nonce, 9); fail("Wrong Binder interface accepted") } catch (_: SecurityException) { }
        } finally { data.recycle() }
    }
}
