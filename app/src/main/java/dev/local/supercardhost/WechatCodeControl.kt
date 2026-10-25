package dev.local.supercardhost

import android.os.Binder
import android.os.Parcel
import android.os.Process

/** An ACK from an arbitrary receiver never authorizes disclosure. Only the reverse system call does. */
internal class WechatCodeControl(private val nonce: String, private val display: Int,
    private val challenge: String, private val stop: () -> Unit) : Binder() {
    @Volatile var authorized = false; private set
    @Volatile private var closed = false
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != WechatCodeWire.AUTHORIZE && code != WechatCodeWire.STOP) return super.onTransact(code, data, reply, flags)
        var accepted = false
        try {
            if (Binder.getCallingUid() == Process.SYSTEM_UID && !closed && WechatCodeWire.check(data, nonce, display)) {
                if (code == WechatCodeWire.AUTHORIZE && data.readString() == challenge && data.dataAvail() == 0) {
                    authorized = true; accepted = true
                } else if (code == WechatCodeWire.STOP && data.dataAvail() == 0) {
                    close(); accepted = true; stop()
                }
            }
        } catch (_: Exception) { }
        reply?.writeNoException(); reply?.writeInt(if (accepted) 1 else 0)
        return true
    }
    fun close() { closed = true; authorized = false }
}
