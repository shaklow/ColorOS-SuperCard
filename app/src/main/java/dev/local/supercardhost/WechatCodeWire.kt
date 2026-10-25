package dev.local.supercardhost

import android.content.ComponentName
import android.os.Binder
import android.os.Parcel
import java.security.SecureRandom

/** Per-launch capability protocol. No component, broadcast, file or network carries payment data. */
internal object WechatCodeWire {
    const val EXTRA = "dev.local.supercardhost.WECHAT_CODE_PORT"
    const val DESCRIPTOR = "dev.local.supercardhost.WechatCodePort.v1"
    const val HELLO = Binder.FIRST_CALL_TRANSACTION
    const val CODE = HELLO + 1
    const val CLEAR = HELLO + 2
    const val AUTHORIZE = HELLO + 3
    const val STOP = HELLO + 4
    const val MAX_PARCEL = 2048
    fun nonce(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun token(value: String?) = value != null && value.matches(Regex("[0-9a-f]{64}"))
    fun payment(value: String?) = value != null && value.matches(Regex("1[0-5][0-9]{16}"))
    fun function(value: String) = value == "wx_pay" || value == "wx2_pay"
    fun page(value: ComponentName?) = value?.packageName == "com.tencent.mm" && value.className in setOf(
        "com.tencent.mm.framework.app.UIPageFragmentActivity", "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI")
    fun header(data: Parcel, nonce: String, display: Int) { data.writeInterfaceToken(DESCRIPTOR); data.writeString(nonce); data.writeInt(display) }
    fun check(data: Parcel, nonce: String, display: Int): Boolean {
        if (data.dataSize() > MAX_PARCEL) return false
        data.enforceInterface(DESCRIPTOR)
        return data.readString() == nonce && data.readInt() == display
    }
}

/** Strict ordering and freshness, independent of Android's IPC implementation. */
internal class WechatCodeOrder {
    var sequence = 0L; private set
    var closed = false; private set
    fun accept(next: Long, timestamp: Long, now: Long): Boolean {
        if (closed || next <= sequence || timestamp < 0 || timestamp > now || now - timestamp > 10_000) return false
        sequence = next; return true
    }
    fun close() { closed = true }
}
